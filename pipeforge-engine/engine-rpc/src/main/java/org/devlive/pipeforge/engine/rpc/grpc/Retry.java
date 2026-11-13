/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Sends a request again when the other side did not answer (unavailable or past its deadline), up
 * to three times with exponential backoff and jitter. Requests keep their request ID, so the other
 * side runs each at most once.
 */
public final class Retry {

    /** Retries after the first attempt. */
    public static final int RETRIES = 3;

    private final ScheduledExecutorService scheduler;
    private final Duration firstBackoff;

    public Retry(ScheduledExecutorService scheduler, Duration firstBackoff) {
        this.scheduler = scheduler;
        this.firstBackoff = firstBackoff;
    }

    /** The result of {@code call}, retried as long as the other side did not answer. */
    public <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> call) {
        CompletableFuture<T> result = new CompletableFuture<>();
        attempt(call, 0, result);
        return result;
    }

    @SuppressWarnings("FutureReturnValueIgnored") // the callback completes another future
    private <T> void attempt(Supplier<CompletableFuture<T>> call, int retry, CompletableFuture<T> result) {
        CompletableFuture<T> future;
        try {
            future = call.get();
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
            return;
        }
        future.whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else if (retry < RETRIES && RpcErrors.retryable(error)) {
                long base = firstBackoff.toMillis() << retry;
                long delay = base + ThreadLocalRandom.current().nextLong(base / 2 + 1);
                scheduler.schedule(() -> attempt(call, retry + 1, result), delay, TimeUnit.MILLISECONDS);
            } else {
                result.completeExceptionally(error);
            }
        });
    }
}
