/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Gives a request that arrives again with the same request ID the result of the first one, for ten
 * minutes, so that a retried request does not run twice (protocol specification, section 2.2).
 * Concurrent duplicates wait for the first.
 */
public final class IdempotencyCache {

    /** How long a result is kept. */
    public static final Duration RETENTION = Duration.ofMinutes(10);

    private record Entry(CompletableFuture<?> result, long expiresNanos) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final LongSupplier nanoTime;

    public IdempotencyCache() {
        this(System::nanoTime);
    }

    IdempotencyCache(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /** The result of {@code requestId}: computed by {@code call} the first time, the same later. */
    // the cast: an ID is only ever reused by the same request; the callback completes another future
    @SuppressWarnings({"unchecked", "FutureReturnValueIgnored"})
    public <T> CompletableFuture<T> run(String requestId, Supplier<CompletableFuture<T>> call) {
        long now = nanoTime.getAsLong();
        CompletableFuture<T> created;
        synchronized (this) {
            evict(now);
            Entry existing = entries.get(requestId);
            if (existing != null) {
                return (CompletableFuture<T>) existing.result();
            }
            created = new CompletableFuture<>();
            entries.put(requestId, new Entry(created, now + RETENTION.toNanos()));
        }
        try {
            call.get().whenComplete((value, error) -> {
                if (error != null) {
                    created.completeExceptionally(error);
                } else {
                    created.complete(value);
                }
            });
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
        }
        return created;
    }

    /** The number of results kept. */
    synchronized int size() {
        evict(nanoTime.getAsLong());
        return entries.size();
    }

    /** Entries are kept in arrival order, so the expired ones are at the front. */
    private void evict(long now) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext() && iterator.next().expiresNanos() - now <= 0) {
            iterator.remove();
        }
    }
}
