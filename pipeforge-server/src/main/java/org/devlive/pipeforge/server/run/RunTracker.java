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
    private ScheduledExecutorService timer;
    private volatile boolean unreachable;

    public RunTracker(
            RunRepository runs,
            MasterClient master,
            Clock clock,
            org.devlive.pipeforge.server.config.ServerProperties properties) {
        this.runs = runs;
        this.master = master;
        this.clock = clock;
        this.interval = properties.master().pollInterval();
    }

    /** Asks the master about every run that has not ended and records what changed. */
    public void poll() {
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
                }
            }
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
