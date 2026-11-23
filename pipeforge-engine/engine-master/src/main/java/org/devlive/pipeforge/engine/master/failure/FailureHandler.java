/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.failure;

import java.time.Duration;
import java.util.function.LongSupplier;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.RestartSettings;

/**
 * What a run does when a task or the job master fails (design 04 section 10.2, 34 section 2). The
 * kind of the error decides, as its code registers it or the thrower or the plugin's {@link
 * org.devlive.pipeforge.api.capability.ErrorClassifier} set it:
 *
 * <ul>
 *   <li>{@link ErrorKind#TRANSIENT} errors (connections, resources, lost workers) restart the run
 *       as long as the restart strategy allows;
 *   <li>{@link ErrorKind#DATA} errors reach the job master only once the dirty data policy gave up
 *       on them, so they fail the run, as retrying would read the same bad rows again;
 *   <li>{@link ErrorKind#FATAL} errors (configuration, plans, plugins, authentication, metadata)
 *       fail the run, as retrying cannot help.
 * </ul>
 *
 * <p>Only the failures that could restart count against the strategy. Used on the job master
 * thread.
 */
public final class FailureHandler {

    /** What to do about a failure. */
    public sealed interface Decision {
        /** The error that caused it. */
        PipeForgeException error();
    }

    /** Restart after {@code delay}. */
    public record Restart(PipeForgeException error, Duration delay) implements Decision {}

    /**
     * Fail the run.
     *
     * @param exhausted whether the error could have been retried but the strategy allows no more
     *     restarts
     */
    public record Fail(PipeForgeException error, boolean exhausted) implements Decision {}

    private final RestartStrategy strategy;
    private final LongSupplier clock;
    private int restarts;

    public FailureHandler(RestartSettings settings, LongSupplier clock) {
        this(RestartStrategy.of(settings), clock);
    }

    FailureHandler(RestartStrategy strategy, LongSupplier clock) {
        this.strategy = strategy;
        this.clock = clock;
    }

    /** Decides what to do about {@code error}. */
    public Decision onFailure(PipeForgeException error) {
        if (error.kind() != ErrorKind.TRANSIENT) {
            return new Fail(error, false);
        }
        return strategy.onFailure(clock.getAsLong())
                .<Decision>map(delay -> {
                    restarts++;
                    return new Restart(error, delay);
                })
                .orElseGet(() -> new Fail(error, true));
    }

    /** The restarts decided so far. */
    public int restarts() {
        return restarts;
    }
}
