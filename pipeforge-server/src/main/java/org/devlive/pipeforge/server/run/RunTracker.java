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
        for (Runs.Run run : runs.live()) {
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
                PipeForgeException lost = PipeForgeException.of(ErrorCode.RUN_LOST)
                        .param("run", run.id())
                        .param("master", master.address())
                        .build();
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
            if (now.state() != run.status()) {
                String reason = now.error() != null ? now.error().code() : "reported by the master";
                if (runs.transition(
                        run.id(), run.status(), now.state(), clock.instant(), now.started(), now.error(), reason)) {
                    LOG.info("Run {} of job {}: {} -> {}", run.id(), run.job(), run.status(), now.state());
                    if (now.state().isTerminal()) {
                        keep(run.id());
                    }
                }
            }
            if (!now.state().isTerminal() && now.state() != RunState.QUEUE) {
                java.time.Instant last = sampled.get(run.id());
                if (last == null || !last.plus(metricsInterval).isAfter(clock.instant())) {
                    sample(run.id());
                }
            }
        }
    }

    /** Keeps a sample of a run's metrics. */
    private void sample(String runId) {
        try {
            master.metrics(runId).ifPresent(metrics -> {
                runs.sample(runId, metrics, json.writeValueAsString(metrics));
                sampled.put(runId, clock.instant());
            });
        } catch (PipeForgeException e) {
            LOG.debug("No metrics of run {}: {}", runId, e.getMessage());
        }
    }

    /** Keeps the final metrics and the run analysis of a run that ended. */
    private void keep(String runId) {
        sample(runId);
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
