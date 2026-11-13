/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.engine.api.JobId;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.master.RunStates.State;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry of the master for the server: it accepts runs, gives each one a {@link JobMaster},
 * follows its states and keeps the runs that ended for a while. Submitting the same run ID again
 * returns the run that exists, so a retried submission does not start a second run.
 */
public final class Dispatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);

    /**
     * A run to start (protocol specification, section 2.1: the master plans the definition again
     * with the plugin versions the server chose).
     *
     * @param plugins plugin ID to the version the server planned with
     * @param timeout how long the run may take; {@code null} for no limit
     */
    public record Submission(
            String jobId,
            String runId,
            String definition,
            Map<String, String> variables,
            @Nullable Instant scheduledTime,
            Map<String, String> plugins,
            @Nullable Duration timeout) {

        public Submission {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(definition, "definition");
            variables = Map.copyOf(variables);
            plugins = Map.copyOf(plugins);
        }
    }

    /** Turns a submission into a plan whose components stay usable until it is closed. */
    @FunctionalInterface
    public interface Planning {
        Planned plan(Submission submission);
    }

    /** A plan and what keeps its components loaded. */
    public record Planned(JobPlan plan, AutoCloseable components) {}

    /** Where the tasks of a run go. */
    @FunctionalInterface
    public interface Placements {
        Placement forRun(JobPlan plan);
    }

    /** A run as the server sees it. */
    public record RunStatus(
            String runId,
            String jobId,
            String jobName,
            State state,
            List<RunStates.Transition> transitions,
            @Nullable PipeForgeException error,
            List<JobMaster.TaskView> tasks) {

        public Instant started() {
            return transitions.get(0).at();
        }

        /** When the run ended, or {@code null} while it runs. */
        public @Nullable Instant ended() {
            return state.isTerminal() ? transitions.get(transitions.size() - 1).at() : null;
        }
    }

    private final class Run {
        final Submission submission;
        final JobPlan plan;
        final JobMaster master;
        final RunStates states;

        @Nullable
        ScheduledFuture<?> timer;

        volatile boolean timedOut;
        volatile @Nullable JobResult result;
        /** Taken when the run reaches its final state, on the job master thread. */
        volatile @Nullable PipeForgeException error;

        Run(Submission submission, JobPlan plan, JobMaster master) {
            this.submission = submission;
            this.plan = plan;
            this.master = master;
            this.states = new RunStates(clock.instant());
        }

        RunStatus status() {
            return new RunStatus(
                    submission.runId(),
                    submission.jobId(),
                    plan.name(),
                    states.state(),
                    states.transitions(),
                    error,
                    master.tasks());
        }
    }

    private final Planning planning;
    private final Placements placements;
    private final JobMasters jobs;
    private final SubmitOptions options;
    private final ScheduledExecutorService timers;
    private final Clock clock;
    private final int retained;
    private final Map<String, Run> runs = new LinkedHashMap<>();

    /**
     * @param timers runs the timeouts of runs
     * @param retained how many ended runs are kept
     */
    public Dispatcher(
            Planning planning,
            Placements placements,
            JobMasters jobs,
            SubmitOptions options,
            ScheduledExecutorService timers,
            Clock clock,
            int retained) {
        this.planning = planning;
        this.placements = placements;
        this.jobs = jobs;
        this.options = options;
        this.timers = timers;
        this.clock = clock;
        this.retained = retained;
    }

    /**
     * Accepts a run and starts it; the run of an ID that exists already is returned as it is.
     *
     * @throws PipeForgeException when the definition cannot be planned
     */
    @SuppressWarnings("FutureReturnValueIgnored") // the callback records the end of the run
    public RunStatus submit(Submission submission) {
        synchronized (this) {
            Run existing = runs.get(submission.runId());
            if (existing != null) {
                return existing.status();
            }
        }
        Planned planned = planning.plan(submission);
        Run run;
        synchronized (this) {
            Run existing = runs.get(submission.runId());
            if (existing != null) {
                close(planned.components());
                return existing.status();
            }
            JobPlan plan = planned.plan();
            JobMaster master = new JobMaster(
                    new JobId(submission.runId()),
                    plan,
                    ChainTopology.chain(plan),
                    options,
                    placements.forRun(plan),
                    () -> jobs.remove(submission.runId()));
            run = new Run(submission, plan, master);
            runs.put(submission.runId(), run);
            trim();
        }
        Run started = run;
        started.master.onStatus(status -> moved(started, status));
        started.master.handle().result().whenComplete((result, error) -> ended(started, result, planned));
        if (submission.timeout() != null) {
            started.timer =
                    timers.schedule(() -> timeout(started), submission.timeout().toMillis(), TimeUnit.MILLISECONDS);
        }
        jobs.add(started.master);
        started.master.start();
        LOG.info("Run {} of job {} accepted", submission.runId(), started.plan.name());
        return started.status();
    }

    /**
     * Cancels a run; a run that has ended is left as it is.
     *
     * @throws PipeForgeException {@link ErrorCode#RUN_NOT_FOUND} for an unknown run
     */
    public RunStatus cancel(String runId, String reason) {
        Run run = find(runId);
        if (!run.states.state().isTerminal()) {
            LOG.info("Cancelling run {}: {}", runId, reason);
            run.master.cancel();
        }
        return run.status();
    }

    /**
     * The state of a run.
     *
     * @throws PipeForgeException {@link ErrorCode#RUN_NOT_FOUND} for an unknown run
     */
    public RunStatus status(String runId) {
        return find(runId).status();
    }

    /**
     * The current metrics of a run.
     *
     * @throws PipeForgeException {@link ErrorCode#RUN_NOT_FOUND} for an unknown run
     */
    public JobMetrics metrics(String runId) {
        return find(runId).master.handle().metrics();
    }

    /** The runs in {@code states} (all when empty), newest first, at most {@code limit}. */
    public synchronized List<RunStatus> list(Set<State> states, int limit) {
        List<RunStatus> list = new ArrayList<>();
        for (Run run : runs.values()) {
            if (states.isEmpty() || states.contains(run.states.state())) {
                list.add(run.status());
            }
        }
        list.sort(Comparator.comparing(RunStatus::started).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    private synchronized Run find(String runId) {
        Run run = runs.get(runId);
        if (run == null) {
            throw PipeForgeException.of(ErrorCode.RUN_NOT_FOUND)
                    .param("run", runId)
                    .build();
        }
        return run;
    }

    private void moved(Run run, JobStatus status) {
        State next =
                switch (status) {
                    case CREATED -> State.QUEUE;
                    case RUNNING -> State.RUNNING;
                    case CANCELLING -> State.CANCELLING;
                    case SUCCESS -> State.SUCCESS;
                    case FAILED -> State.FAILED;
                    case CANCELLED -> run.timedOut ? State.TIMEOUT : State.CANCELLED;
                };
        String reason =
                switch (next) {
                    case RUNNING -> "tasks deployed";
                    case CANCELLING -> run.timedOut ? "timeout of " + run.submission.timeout() : "cancel requested";
                    default -> status.name().toLowerCase(java.util.Locale.ROOT);
                };
        if (next.isTerminal()) {
            run.error = run.master.failure();
        }
        run.states.move(next, clock.instant(), reason);
    }

    private void ended(Run run, @Nullable JobResult result, Planned planned) {
        run.result = result;
        if (run.timer != null) {
            run.timer.cancel(false);
        }
        close(planned.components());
        LOG.info("Run {} of job {} ended: {}", run.submission.runId(), run.plan.name(), run.states.state());
    }

    private void timeout(Run run) {
        if (!run.states.state().isTerminal()) {
            LOG.info("Run {} of job {} timed out", run.submission.runId(), run.plan.name());
            run.timedOut = true;
            run.master.cancel();
        }
    }

    /** Forgets the oldest runs that ended once more than {@code retained} are kept. */
    private void trim() {
        long ended = runs.values().stream()
                .filter(r -> r.states.state().isTerminal())
                .count();
        var iterator = runs.values().iterator();
        while (ended > retained && iterator.hasNext()) {
            if (iterator.next().states.state().isTerminal()) {
                iterator.remove();
                ended--;
            }
        }
    }

    private static void close(AutoCloseable components) {
        try {
            components.close();
        } catch (Exception e) {
            LOG.warn("Releasing the plugins of a run failed", e);
        }
    }

    /** Cancels the runs that have not ended. */
    @Override
    public synchronized void close() {
        runs.values().stream().filter(r -> !r.states.state().isTerminal()).forEach(r -> r.master.cancel());
    }

    /** {@code runId} of a run that has ended, if the dispatcher still knows it. */
    synchronized Optional<JobResult> result(String runId) {
        Run run = runs.get(runId);
        return run == null ? Optional.empty() : Optional.ofNullable(run.result);
    }
}
