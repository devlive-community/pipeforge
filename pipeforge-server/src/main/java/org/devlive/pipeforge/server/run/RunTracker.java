/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.Delivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Follows the runs that have not ended: every poll interval it asks the master where each is and
 * records every change of state. A run the master no longer knows failed ({@code PF-CLUS-0016}),
 * unless it is still in {@code QUEUE} within {@link #SUBMIT_GRACE} of its submission: the master
 * may not have received it yet. While the master cannot be reached, runs keep their state.
 *
 * <p>An attempt that failed is followed by another when the job's {@code env.retry} allows: the run
 * waits in {@code RETRY_WAIT} for the backoff, then the next attempt is submitted under the same run
 * ID, from the run's last completed checkpoint or from the start.
 *
 * <p>Every metrics interval it keeps a sample of each running run's metrics in {@code
 * job_run_metrics}, and when a run ends its final metrics and its run analysis, so that they
 * outlive the master's memory of the run. Samples older than the retention are removed hourly.
 */
@Component
public class RunTracker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(RunTracker.class);

    /** How long a submission may take to reach the master: more than the submit call's timeout. */
    static final Duration SUBMIT_GRACE = Duration.ofSeconds(30);

    private final RunRepository runs;
    private final MasterClient master;
    private final Clock clock;
    private final Duration interval;
    private final Duration metricsInterval;
    private final Duration retention;
    private final tools.jackson.databind.json.JsonMapper json;
    private final java.util.Map<String, java.time.Instant> sampled = new java.util.concurrent.ConcurrentHashMap<>();
    private java.time.Instant cleaned = java.time.Instant.EPOCH;
    private ScheduledExecutorService timer;
    private volatile boolean unreachable;

    public RunTracker(
            RunRepository runs,
            MasterClient master,
            Clock clock,
            org.devlive.pipeforge.server.config.ServerProperties properties,
            tools.jackson.databind.json.JsonMapper json) {
        this.runs = runs;
        this.master = master;
        this.clock = clock;
        this.interval = properties.master().pollInterval();
        this.metricsInterval = properties.master().metricsInterval();
        this.retention = properties.metastore().metricsRetention();
        this.json = json;
    }

    /** Asks the master about every run that has not ended and records what changed. */
    public void poll() {
        cleanup();
        retryDue();
        for (Runs.Run run : runs.live()) {
            if (run.status() == RunState.RETRY_WAIT) {
                // between two attempts: the master has nothing to say
                continue;
            }
            Optional<MasterClient.Status> status;
            try {
                status = master.status(run.id());
            } catch (PipeForgeException e) {
                if (e.code().equals(ErrorCode.MASTER_NOT_CONFIGURED)) {
                    return;
                }
                if (!unreachable) {
                    LOG.warn("Cannot follow the runs on {}: {}", master.address(), e.getMessage());
                    unreachable = true;
                }
                return;
            }
            unreachable = false;
            if (status.isEmpty()) {
                if (run.status() == RunState.QUEUE
                        && run.submittedAt().plus(SUBMIT_GRACE).isAfter(clock.instant())) {
                    continue;
                }
                if (run.status() == RunState.PAUSED) {
                    // nothing runs: resuming submits it again from its savepoint, on whichever master leads
                    continue;
                }
                if (run.status() == RunState.CANCELLING) {
                    // it was being cancelled when its master went away: nothing of it runs any more
                    runs.transition(
                            run.id(),
                            RunState.CANCELLING,
                            RunState.CANCELLED,
                            clock.instant(),
                            null,
                            null,
                            "the master that ran it is gone");
                    continue;
                }
                if (recover(run)) {
                    continue;
                }
                PipeForgeException lost = PipeForgeException.of(ErrorCode.RUN_LOST)
                        .param("run", run.id())
                        .param("master", master.address())
                        .build();
                if (retry(run, new Runs.RunError(ErrorCode.RUN_LOST.id(), lost.getMessage()))) {
                    continue;
                }
                runs.transition(
                        run.id(),
                        run.status(),
                        RunState.FAILED,
                        clock.instant(),
                        null,
                        new Runs.RunError(ErrorCode.RUN_LOST.id(), lost.getMessage()),
                        ErrorCode.RUN_LOST.id());
                continue;
            }
            MasterClient.Status now = status.get();
            if (now.attempt() < run.attempt()) {
                // the master has not taken the run's new attempt yet
                continue;
            }
            if (now.state() == RunState.FAILED && now.error() != null && retry(run, now.error())) {
                continue;
            }
            if (run.status() == RunState.PAUSING
                    && now.state() == RunState.RUNNING
                    && run.updatedAt().plus(GrpcMasterClient.SAVEPOINT_TIMEOUT).isAfter(clock.instant())) {
                // the pause is on its way to the master, which still reports the run as running
                continue;
            }
            if (now.state() != run.status()) {
                String reason = now.error() != null ? now.error().code() : "reported by the master";
                if (runs.transition(
                        run.id(), run.status(), now.state(), clock.instant(), now.started(), now.error(), reason)) {
                    LOG.info("Run {} of job {}: {} -> {}", run.id(), run.job(), run.status(), now.state());
                    if (now.state().isTerminal()) {
                        keep(run.id(), run.attempt());
                    }
                }
            }
            if (!now.state().isTerminal() && now.state() != RunState.QUEUE && now.state() != RunState.PAUSED) {
                java.time.Instant last = sampled.get(run.id());
                if (last == null || !last.plus(metricsInterval).isAfter(clock.instant())) {
                    sample(run.id(), run.attempt());
                }
            }
        }
    }

    // ------------------------------------------------------------------ recovery

    /**
     * The master does not know a run that has not ended: another master took over, or the master
     * restarted. The run goes on there in the same attempt, from its last completed checkpoint, or
     * from the start when the job is delivered exactly once and has none; otherwise it is lost.
     *
     * @return whether the run goes on
     */
    private boolean recover(Runs.Run run) {
        Optional<RunRetries.Resubmission> kept = resubmission(run.id());
        if (kept.isEmpty()) {
            return false;
        }
        RunRetries.Resubmission submission = kept.get();
        String restore = runs.lastCheckpoint(run.id()).orElse(null);
        if (restore == null && !submission.delivery().equals(Delivery.EXACTLY_ONCE.id())) {
            LOG.info(
                    "Run {} of job {} is lost on {}: it has no completed checkpoint to go on from",
                    run.id(),
                    run.job(),
                    master.address());
            return false;
        }
        String reason =
                "recovered on master " + master.address() + (restore == null ? " from the start" : " from " + restore);
        try {
            master.submit(new MasterClient.Submission(
                    run.job(),
                    run.id(),
                    runs.detail(run.id()).definition(),
                    submission.variables(),
                    java.time.Instant.ofEpochMilli(submission.scheduledMs()),
                    submission.plugins(),
                    run.attempt(),
                    restore,
                    submission.timeLeft(runs.runningMs(run.id()))));
        } catch (PipeForgeException e) {
            if (e.code().equals(ErrorCode.NOT_LEADER) || e.code().equals(ErrorCode.RPC_UNAVAILABLE)) {
                // the masters are still changing over: the next poll tries again
                LOG.info("Run {} waits for a leading master to recover it: {}", run.id(), e.getMessage());
                return true;
            }
            LOG.warn("The master did not take run {} back: {}", run.id(), e.getMessage());
            return false;
        }
        runs.transition(run.id(), run.status(), RunState.QUEUE, clock.instant(), null, null, reason);
        LOG.warn("Run {} of job {} was not known to the master: {}", run.id(), run.job(), reason);
        return true;
    }

    // ------------------------------------------------------------------ retries

    /**
     * An attempt failed with {@code error}: when {@code env.retry} allows another, the run waits in
     * {@code RETRY_WAIT}.
     *
     * @return whether another attempt follows
     */
    private boolean retry(Runs.Run run, Runs.RunError error) {
        Optional<RunRetries.Resubmission> kept = resubmission(run.id());
        if (kept.isEmpty()) {
            return false;
        }
        RunRetries.Decision decision = RunRetries.decide(
                run,
                error.code(),
                kept.get(),
                runs.lastCheckpoint(run.id()).isPresent(),
                clock.instant(),
                runs.runningMs(run.id()));
        if (!decision.retry()) {
            LOG.info("Run {} of job {} failed: {}", run.id(), run.job(), decision.reason());
            return false;
        }
        if (runs.awaitRetry(
                run.id(),
                run.status(),
                clock.instant(),
                java.util.Objects.requireNonNull(decision.at()),
                error,
                decision.reason())) {
            LOG.info("Run {} of job {}: attempt {} failed, {}", run.id(), run.job(), run.attempt(), decision.reason());
            keep(run.id(), run.attempt());
        }
        return true;
    }

    /** Submits the next attempt of every run whose retry is due. */
    private void retryDue() {
        for (Runs.Run run : runs.dueRetries(clock.instant())) {
            Optional<RunRetries.Resubmission> kept = resubmission(run.id());
            if (kept.isEmpty()) {
                continue;
            }
            RunRetries.Resubmission submission = kept.get();
            int attempt = run.attempt() + 1;
            String restore = submission.retry().resume().equals("restart")
                    ? null
                    : runs.lastCheckpoint(run.id()).orElse(null);
            String reason = "attempt " + attempt + (restore == null ? " from the start" : " from " + restore);
            if (!runs.startAttempt(run.id(), attempt, restore, clock.instant(), reason)) {
                continue;
            }
            String definition = runs.detail(run.id()).definition();
            try {
                master.submit(new MasterClient.Submission(
                        run.job(),
                        run.id(),
                        definition,
                        submission.variables(),
                        java.time.Instant.ofEpochMilli(submission.scheduledMs()),
                        submission.plugins(),
                        attempt,
                        restore,
                        submission.timeLeft(runs.runningMs(run.id()))));
                LOG.info("Run {} of job {}: {}", run.id(), run.job(), reason);
            } catch (PipeForgeException e) {
                LOG.warn("The master did not take attempt {} of run {}: {}", attempt, run.id(), e.getMessage());
                runs.transition(
                        run.id(),
                        RunState.QUEUE,
                        RunState.FAILED,
                        clock.instant(),
                        null,
                        new Runs.RunError(e.code().id(), e.getMessage()),
                        e.code().id());
            }
        }
    }

    private Optional<RunRetries.Resubmission> resubmission(String runId) {
        return runs.submission(runId).map(text -> json.readValue(text, RunRetries.Resubmission.class));
    }

    /** Keeps a sample of a run's metrics. */
    private void sample(String runId, int attempt) {
        try {
            master.metrics(runId).ifPresent(metrics -> {
                runs.sample(runId, attempt, metrics, json.writeValueAsString(metrics));
                sampled.put(runId, clock.instant());
            });
        } catch (PipeForgeException e) {
            LOG.debug("No metrics of run {}: {}", runId, e.getMessage());
        }
    }

    /** Keeps the final metrics and the run analysis of an attempt that ended. */
    private void keep(String runId, int attempt) {
        sample(runId, attempt);
        sampled.remove(runId);
        try {
            master.analysis(runId).ifPresent(analysis -> runs.saveAnalysis(runId, analysis));
        } catch (PipeForgeException e) {
            LOG.warn("Cannot keep the analysis of run {}: {}", runId, e.getMessage());
        }
    }

    /** Removes the samples older than the retention, at most once an hour. */
    private void cleanup() {
        java.time.Instant now = clock.instant();
        if (cleaned.plus(Duration.ofHours(1)).isAfter(now)) {
            return;
        }
        cleaned = now;
        int removed = runs.deleteSamplesBefore(now.minus(retention));
        if (removed > 0) {
            LOG.info("Removed {} metric samples older than {}", removed, retention);
        }
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored") // the task runs until stop() shuts the timer down
    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "run-tracker");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(
                () -> {
                    try {
                        poll();
                    } catch (RuntimeException e) {
                        LOG.warn("Following the runs failed", e);
                    }
                },
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return timer != null;
    }
}
