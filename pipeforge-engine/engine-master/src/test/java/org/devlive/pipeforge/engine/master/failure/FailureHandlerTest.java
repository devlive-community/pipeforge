/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.RestartSettings;
import org.junit.jupiter.api.Test;

class FailureHandlerTest {

    private final AtomicLong clock = new AtomicLong();

    private static PipeForgeException connectionLost() {
        return PipeForgeException.of(ErrorCode.CONN_LOST)
                .param("target", "db:3306")
                .param("reason", "reset")
                .build();
    }

    private static PipeForgeException workerLost() {
        return PipeForgeException.of(ErrorCode.WORKER_LOST)
                .param("worker", "w1")
                .param("address", "10.0.0.1:6124")
                .param("seconds", 30)
                .build();
    }

    private static PipeForgeException authFailed() {
        return PipeForgeException.of(ErrorCode.AUTH_FAILED)
                .param("target", "db")
                .param("user", "etl")
                .param("reason", "denied")
                .build();
    }

    private static PipeForgeException tooManyDirtyRows() {
        return PipeForgeException.of(ErrorCode.TOO_MANY_DIRTY_ROWS)
                .param("rows", 11)
                .param("limit", 10)
                .build();
    }

    private static PipeForgeException sinkFailed(ErrorKind kind) {
        return PipeForgeException.of(ErrorCode.SINK_FAILED)
                .param("node", "out")
                .param("reason", "deadlock")
                .kind(kind)
                .build();
    }

    @Test
    void transientErrorsRestartAsTheStrategyAllows() {
        FailureHandler handler = new FailureHandler(RestartSettings.DEFAULTS, clock::get);

        for (PipeForgeException error :
                new PipeForgeException[] {connectionLost(), workerLost(), sinkFailed(ErrorKind.TRANSIENT)}) {
            assertThat(error.kind()).isEqualTo(ErrorKind.TRANSIENT);
            FailureHandler.Decision decision = handler.onFailure(error);
            assertThat(decision).isInstanceOfSatisfying(FailureHandler.Restart.class, restart -> {
                assertThat(restart.delay()).isEqualTo(Duration.ofSeconds(10));
                assertThat(restart.error()).isSameAs(error);
            });
        }
        assertThat(handler.restarts()).isEqualTo(3);

        assertThat(handler.onFailure(connectionLost()))
                .isInstanceOfSatisfying(FailureHandler.Fail.class, fail -> assertThat(fail.exhausted())
                        .isTrue());
        assertThat(handler.restarts()).isEqualTo(3);
    }

    @Test
    void fatalAndDataErrorsFailAtOnceWithoutUsingAttempts() {
        FailureHandler handler = new FailureHandler(RestartSettings.DEFAULTS, clock::get);

        for (PipeForgeException error :
                new PipeForgeException[] {authFailed(), sinkFailed(ErrorKind.FATAL), tooManyDirtyRows()}) {
            assertThat(handler.onFailure(error)).isInstanceOfSatisfying(FailureHandler.Fail.class, fail -> {
                assertThat(fail.exhausted()).isFalse();
                assertThat(fail.error()).isSameAs(error);
            });
        }
        assertThat(tooManyDirtyRows().kind()).isEqualTo(ErrorKind.DATA);
        assertThat(handler.restarts()).isZero();
        assertThat(handler.onFailure(connectionLost()))
                .as("the attempts are all still there")
                .isInstanceOf(FailureHandler.Restart.class);
    }

    @Test
    void sourceAndSinkErrorsAreFatalUnlessMarkedTransient() {
        assertThat(sinkFailed(ErrorKind.FATAL).kind()).isEqualTo(ErrorKind.FATAL);
        PipeForgeException unmarked = PipeForgeException.of(ErrorCode.SOURCE_FAILED)
                .param("node", "in")
                .param("reason", "boom")
                .build();
        assertThat(new FailureHandler(RestartSettings.DEFAULTS, clock::get).onFailure(unmarked))
                .isInstanceOf(FailureHandler.Fail.class);
    }

    @Test
    void theStrategySeesTheClock() {
        RestartSettings settings = new RestartSettings(
                RestartSettings.Strategy.FAILURE_RATE,
                3,
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                2,
                Duration.ofHours(1),
                1,
                Duration.ofMinutes(1));
        FailureHandler handler = new FailureHandler(settings, clock::get);

        assertThat(handler.onFailure(connectionLost())).isInstanceOf(FailureHandler.Restart.class);
        clock.addAndGet(Duration.ofSeconds(59).toMillis());
        assertThat(handler.onFailure(connectionLost())).isInstanceOf(FailureHandler.Fail.class);

        FailureHandler later = new FailureHandler(settings, clock::get);
        clock.set(0);
        assertThat(later.onFailure(connectionLost())).isInstanceOf(FailureHandler.Restart.class);
        clock.addAndGet(Duration.ofMinutes(1).toMillis());
        assertThat(later.onFailure(connectionLost()))
                .as("the first failure left the window")
                .isInstanceOf(FailureHandler.Restart.class);
    }

    @Test
    void noneFailsTransientErrorsAsExhausted() {
        FailureHandler handler = new FailureHandler(RestartSettings.of(RestartSettings.Strategy.NONE), clock::get);
        assertThat(handler.onFailure(workerLost()))
                .isInstanceOfSatisfying(FailureHandler.Fail.class, fail -> assertThat(fail.exhausted())
                        .isTrue());
    }
}
