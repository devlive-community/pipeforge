/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.server.config.JobPlanning;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Submits runs to the master and records them in the metastore. A job is planned on the server
 * first, so that an invalid job is refused before it becomes a run, and the master plans it again
 * with the plugin versions the server chose.
 */
@Service
public class RunService {

    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);

    private final JobPlanning planning;
    private final RunRepository runs;
    private final MasterClient master;
    private final Clock clock;
    private final tools.jackson.databind.json.JsonMapper json;

    public RunService(
            JobPlanning planning,
            RunRepository runs,
            MasterClient master,
            Clock clock,
            tools.jackson.databind.json.JsonMapper json) {
        this.planning = planning;
        this.runs = runs;
        this.master = master;
        this.clock = clock;
        this.json = json;
    }

    private Runs.Run run(String id) {
        return runs.find(id).orElseThrow(() -> RunRepository.notFound(id));
    }

    /** Takes the savepoints of pauses, which can last as long as a savepoint does. */
    private final java.util.concurrent.ExecutorService pauses =
            java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "run-pause");
                thread.setDaemon(true);
                return thread;
            });

    @jakarta.annotation.PreDestroy
    void stop() {
        pauses.shutdownNow();
    }

    /**
     * The checkpoints and savepoints of a run, newest first.
     *
     * @throws PipeForgeException {@code PF-CONF-0010} for an unknown run
     */
    public java.util.List<Runs.Checkpoint> checkpoints(String id) {
        run(id);
        return runs.checkpoints(id);
    }

    /**
     * The attempts of a run, oldest first, with what each did as its last metrics sample has it.
     *
     * @throws PipeForgeException {@code PF-CONF-0010} for an unknown run
     */
    public java.util.List<Runs.AttemptView> attempts(String id) {
        Runs.RunDetail detail = runs.detail(id);
        java.util.Map<Integer, String> metrics = runs.attemptMetrics(id);
        return detail.attempts().stream()
                .map(a -> {
                    RunMetrics sample = metrics.containsKey(a.attempt())
                            ? json.readValue(metrics.get(a.attempt()), RunMetrics.class)
                            : null;
                    boolean waiting = a.attempt() == detail.run().attempt()
                            && detail.run().status() == RunState.RETRY_WAIT;
                    return new Runs.AttemptView(
                            a.attempt(),
                            a.status(),
                            a.startedAt(),
                            a.endedAt(),
                            a.error(),
                            a.restorePath(),
                            waiting ? detail.run().retryAt() : null,
                            sample == null ? null : sample.rowsRead(),
                            sample == null ? null : sample.rowsWritten(),
                            sample == null ? null : sample.rowsDirty(),
                            sample == null ? null : sample.elapsedMs());
                })
                .toList();
    }

    /**
     * Takes a savepoint of a running run and returns it once it completed; the run goes on.
     *
     * @throws PipeForgeException {@code PF-CONF-0007} when the run is not running
     */
    public Runs.Checkpoint savepoint(String id, @Nullable String name) {
        Runs.Run run = run(id);
        if (run.status() != RunState.RUNNING) {
            throw invalid("run " + id + " is " + run.status() + "; only a running run takes a savepoint");
        }
        MasterClient.Savepoint taken = master.savepoint(id, blank(name));
        return runs.checkpoints(id).stream()
                .filter(c -> c.id() == taken.checkpointId())
                .findFirst()
                .orElseThrow(() -> invalid("savepoint " + taken.checkpointId() + " of run " + id + " is not recorded"));
    }

    /**
     * Pauses a running run: it moves to PAUSING at once, and to PAUSED once the master stopped it
     * with a savepoint; when the savepoint fails it reads on.
     *
     * @throws PipeForgeException {@code PF-CONF-0007} when the run is not running
     */
    @SuppressWarnings("FutureReturnValueIgnored") // the tracker follows the run's state
    public Runs.Run pause(String id, @Nullable String name) {
        Runs.Run run = run(id);
        if (run.status() == RunState.PAUSING || run.status() == RunState.PAUSED) {
            return run;
        }
        if (run.status() != RunState.RUNNING) {
            throw invalid("run " + id + " is " + run.status() + "; only a running run can be paused");
        }
        if (!runs.transition(id, RunState.RUNNING, RunState.PAUSING, clock.instant(), null, null, "pause asked")) {
            return run(id);
        }
        pauses.submit(() -> {
            try {
                MasterClient.Savepoint taken = master.pause(id, blank(name));
                if (runs.transition(
                        id,
                        RunState.PAUSING,
                        RunState.PAUSED,
                        clock.instant(),
                        null,
                        null,
                        "paused at savepoint " + taken.checkpointId() + " " + taken.path())) {
                    LOG.info("Run {} of job {} is paused at {}", id, run.job(), taken.path());
                }
            } catch (PipeForgeException e) {
                // the master reads on when the savepoint fails, or the run ended meanwhile
                LOG.warn("Run {} could not be paused: {}", id, e.getMessage());
                runs.transition(
                        id,
                        RunState.PAUSING,
                        RunState.RUNNING,
                        clock.instant(),
                        null,
                        null,
                        "pause failed: " + e.getMessage());
            }
        });
        return run(id);
    }

    /**
     * Resumes a paused run from the savepoint it was paused at, in the same attempt.
     *
     * @throws PipeForgeException {@code PF-CONF-0007} when the run is not paused
     */
    public Runs.Run resume(String id) {
        Runs.Run run = run(id);
        if (run.status() != RunState.PAUSED) {
            throw invalid("run " + id + " is " + run.status() + "; only a paused run can be resumed");
        }
        RunRetries.Resubmission submission = runs.submission(id)
                .map(text -> json.readValue(text, RunRetries.Resubmission.class))
                .orElseThrow(() -> invalid("run " + id + " was submitted by an earlier version and cannot be"
                        + " resumed; submit its job instead"));
        String savepoint =
                runs.lastCheckpoint(id).orElseThrow(() -> invalid("run " + id + " has no savepoint to go on from"));
        if (!runs.transition(
                id, RunState.PAUSED, RunState.QUEUE, clock.instant(), null, null, "resumed from " + savepoint)) {
            return run(id);
        }
        try {
            master.submit(new MasterClient.Submission(
                    run.job(),
                    id,
                    runs.detail(id).definition(),
                    submission.variables(),
                    Instant.ofEpochMilli(submission.scheduledMs()),
                    submission.plugins(),
                    run.attempt(),
                    savepoint,
                    submission.timeLeft(runs.runningMs(id))));
        } catch (PipeForgeException e) {
            LOG.warn("The master did not resume run {}: {}", id, e.getMessage());
            runs.transition(
                    id,
                    RunState.QUEUE,
                    RunState.PAUSED,
                    clock.instant(),
                    null,
                    null,
                    "resume failed: " + e.getMessage());
            throw e;
        }
        LOG.info("Run {} of job {} resumed from {}", id, run.job(), savepoint);
        return run(id);
    }

    private static @Nullable String blank(@Nullable String name) {
        return name == null || name.isBlank() ? null : name.strip();
    }

    /**
     * The metrics of a run: from the master while it runs, else the last sample the server kept.
     *
     * @throws PipeForgeException {@code PF-CONF-0010} for an unknown run
     */
    public RunMetrics metrics(String id) {
        Runs.Run run = run(id);
        if (!run.status().isTerminal()) {
            try {
                java.util.Optional<RunMetrics> live = master.metrics(id);
                if (live.isPresent()) {
                    // the counters of the attempts before are added
                    return run.attempt() > 1 ? live.get().after(runs.countsBefore(id, run.attempt())) : live.get();
                }
            } catch (PipeForgeException e) {
                LOG.debug("No live metrics of run {}: {}", id, e.getMessage());
            }
        }
        return runs.latestMetrics(id)
                .map(text -> json.readValue(text, RunMetrics.class))
                .orElseGet(() -> RunMetrics.none(run.endedAt() != null ? run.endedAt() : run.updatedAt()));
    }

    /** The samples of a run's metrics, oldest first. */
    public java.util.List<RunMetrics.Sample> history(String id, @Nullable Instant since, int limit) {
        run(id);
        return runs.samples(id, since, limit);
    }

    /**
     * The run analysis (design 29): kept when the run ended, else from the master.
     *
     * @throws PipeForgeException {@code PF-CONF-0010} for an unknown run, or when neither the
     *     server nor the master has its analysis
     */
    public tools.jackson.databind.JsonNode analysis(String id) {
        run(id);
        String text = runs.analysis(id).or(() -> master.analysis(id)).orElseThrow(() -> RunRepository.notFound(id));
        return json.readTree(text);
    }

    /** The splits of a run with their readers; empty when the master no longer has the run. */
    public java.util.List<RunMetrics.Split> splits(String id) {
        run(id);
        return master.splits(id).orElse(java.util.List.of());
    }

    /** The tasks of a run with their workers; empty when the master no longer has the run. */
    public java.util.List<RunMetrics.Task> tasks(String id) {
        run(id);
        return master.status(id).map(MasterClient.Status::tasks).orElse(java.util.List.of());
    }

    /**
     * Reads a part of a task's log through the master.
     *
     * @throws PipeForgeException {@code PF-CLUS-0012} when there is no such log
     */
    public RunMetrics.LogChunk log(String id, String vertex, int subtask, int attempt, MasterClient.LogQuery query) {
        run(id);
        return master.readLog(id, new RunMetrics.Task(vertex, subtask, attempt, "", ""), query);
    }

    /**
     * Plans the job, records the run and submits it.
     *
     * @throws PipeForgeException what planning reports for an invalid job (no run is recorded), or
     *     why the master did not take the run (the run is recorded as failed)
     */
    public Runs.Run submit(
            String definition, @Nullable Map<String, String> variables, @Nullable Instant scheduledTime) {
        Instant now = clock.instant();
        Instant scheduled = scheduledTime != null ? scheduledTime : now;
        Map<String, String> values = variables == null ? Map.of() : Map.copyOf(variables);
        JobPlan plan = planning.plan(definition, values, scheduled);
        Map<String, String> plugins = new LinkedHashMap<>();
        for (PlannedNode node : plan.nodes()) {
            String coordinate = node.plugin();
            int at = coordinate.lastIndexOf('@');
            if (at > 0) {
                plugins.put(coordinate.substring(0, at), coordinate.substring(at + 1));
            }
        }
        String id = UUID.randomUUID().toString();
        Runs.Run run = runs.insert(
                id,
                plan.name(),
                definition,
                null,
                now,
                json.writeValueAsString(RunRetries.Resubmission.of(plan, values, scheduled, plugins)));
        try {
            master.submit(new MasterClient.Submission(plan.name(), id, definition, values, scheduled, plugins));
        } catch (PipeForgeException e) {
            LOG.warn("The master did not take run {} of job {}: {}", id, plan.name(), e.getMessage());
            runs.transition(
                    id,
                    RunState.QUEUE,
                    RunState.FAILED,
                    clock.instant(),
                    null,
                    new Runs.RunError(e.code().id(), e.getMessage()),
                    e.code().id());
            throw e;
        }
        LOG.info("Submitted run {} of job {} to {}", id, plan.name(), master.address());
        return run;
    }

    /** Where a retry by hand starts. */
    public enum RetryFrom {
        /** From the run's last completed checkpoint; refused without one. */
        CHECKPOINT,
        /** From the beginning. */
        SCRATCH
    }

    /**
     * Starts the next attempt of a run that failed, timed out or was cancelled, whatever {@code
     * env.retry} allows (design 34 section 3.2).
     *
     * @param from where it starts; {@code null} for the last completed checkpoint when the run has
     *     one, else the beginning
     * @throws PipeForgeException {@code PF-CONF-0010} for an unknown run, {@code PF-CONF-0007} for a
     *     run that cannot be retried, or why the master did not take the attempt (the run is then
     *     recorded as failed)
     */
    public Runs.Run retry(String id, @Nullable RetryFrom from) {
        Runs.Run run = run(id);
        if (!run.status().isTerminal() || run.status() == RunState.SUCCESS) {
            throw invalid("run " + id + " is " + run.status() + "; only a run that failed, timed out or was cancelled"
                    + " can be retried");
        }
        RunRetries.Resubmission submission = runs.submission(id)
                .map(text -> json.readValue(text, RunRetries.Resubmission.class))
                .orElseThrow(() -> invalid("run " + id + " was submitted by an earlier version and cannot be"
                        + " submitted again; submit its job instead"));
        java.util.Optional<String> checkpoint = runs.lastCheckpoint(id);
        if (from == RetryFrom.CHECKPOINT && checkpoint.isEmpty()) {
            throw invalid("run " + id + " has no completed checkpoint to go on from; retry it from scratch");
        }
        String restore = from == RetryFrom.SCRATCH ? null : checkpoint.orElse(null);
        int attempt = run.attempt() + 1;
        String reason =
                "retried by hand: attempt " + attempt + (restore == null ? " from the start" : " from " + restore);
        if (!runs.startAttempt(id, run.status(), attempt, restore, clock.instant(), reason)) {
            // another server retried it meanwhile
            return run(id);
        }
        try {
            master.submit(new MasterClient.Submission(
                    run.job(),
                    id,
                    runs.detail(id).definition(),
                    submission.variables(),
                    Instant.ofEpochMilli(submission.scheduledMs()),
                    submission.plugins(),
                    attempt,
                    restore));
        } catch (PipeForgeException e) {
            LOG.warn("The master did not take attempt {} of run {}: {}", attempt, id, e.getMessage());
            runs.transition(
                    id,
                    RunState.QUEUE,
                    RunState.FAILED,
                    clock.instant(),
                    null,
                    new Runs.RunError(e.code().id(), e.getMessage()),
                    e.code().id());
            throw e;
        }
        LOG.info("Run {} of job {}: {}", id, run.job(), reason);
        return run(id);
    }

    private static PipeForgeException invalid(String reason) {
        return PipeForgeException.of(org.devlive.pipeforge.common.error.ErrorCode.REQUEST_INVALID)
                .param("reason", reason)
                .build();
    }

    /**
     * Asks the master to cancel a run that has not ended; an ended run is returned as it is.
     *
     * @throws PipeForgeException {@code PF-CONF-0010} for an unknown run
     */
    public Runs.Run cancel(String id, @Nullable String reason) {
        Runs.Run run = runs.find(id).orElseThrow(() -> RunRepository.notFound(id));
        if (run.status().isTerminal() || run.status() == RunState.CANCELLING) {
            return run;
        }
        String why = reason == null || reason.isBlank() ? "cancelled through the API" : reason;
        master.cancel(id, why);
        runs.transition(id, run.status(), RunState.CANCELLING, clock.instant(), null, null, why);
        return runs.find(id).orElseThrow();
    }
}
