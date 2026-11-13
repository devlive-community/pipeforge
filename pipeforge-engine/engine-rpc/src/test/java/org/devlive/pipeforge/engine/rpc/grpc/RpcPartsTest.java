/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.proto.Header;
import org.junit.jupiter.api.Test;

/** Fencing, idempotency, ordering and headers on their own. */
class RpcPartsTest {

    static Header header(long leader, long job) {
        return Headers.create(new Headers.Epochs(leader, job), Duration.ofSeconds(1), () -> 0);
    }

    @Test
    void fencingRefusesReplacedMasters() {
        Fencing fencing = new Fencing();
        fencing.check(header(1, 1), "r1");
        fencing.check(header(2, 1), "r1");
        assertThatThrownBy(() -> fencing.check(header(1, 1), "r1"))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.RPC_STALE_EPOCH);
                    assertThat(e.params()).containsEntry("epoch", "leader").containsEntry("known", 2L);
                });
        fencing.check(header(2, 3), "r1");
        assertThatThrownBy(() -> fencing.check(header(2, 2), "r1"))
                .isInstanceOfSatisfying(
                        PipeForgeException.class, e -> assertThat(e.params()).containsEntry("epoch", "job"));
        // job epochs are per run
        fencing.check(header(2, 1), "r2");
        fencing.forget("r1");
        fencing.check(header(2, 1), "r1");
    }

    @Test
    void aRequestIdRunsOnceForTenMinutes() {
        AtomicLong now = new AtomicLong();
        IdempotencyCache cache = new IdempotencyCache(now::get);
        AtomicInteger runs = new AtomicInteger();
        CompletableFuture<Integer> pending = new CompletableFuture<>();

        CompletableFuture<Integer> first = cache.run("a", () -> {
            runs.incrementAndGet();
            return pending;
        });
        CompletableFuture<Integer> concurrent = cache.run("a", () -> {
            runs.incrementAndGet();
            return CompletableFuture.completedFuture(2);
        });
        pending.complete(1);
        assertThat(first).isCompletedWithValue(1);
        assertThat(concurrent).isCompletedWithValue(1);

        now.addAndGet(IdempotencyCache.RETENTION.toNanos() - 1);
        assertThat(cache.run("a", () -> CompletableFuture.completedFuture(3))).isCompletedWithValue(1);
        assertThat(runs).hasValue(1);

        now.addAndGet(1);
        assertThat(cache.run("a", () -> CompletableFuture.completedFuture(3))).isCompletedWithValue(3);
        assertThat(cache.size()).isEqualTo(1);

        CompletableFuture<Object> failing = cache.run("b", () -> {
            throw new IllegalStateException("boom");
        });
        assertThat(failing).isCompletedExceptionally();
        assertThat(cache.run("b", () -> CompletableFuture.completedFuture("ok")))
                .as("the same failure, not a second run")
                .isCompletedExceptionally();
    }

    @Test
    void eventsArriveInOrderOnce() {
        Sequencer<String, String> sequencer = new Sequencer<>();
        List<String> delivered = new ArrayList<>();

        assertThat(sequencer.accept("t", 2, "b", delivered::add)).isZero();
        assertThat(sequencer.accept("t", 3, "c", delivered::add)).isZero();
        assertThat(sequencer.accept("t", 2, "b again", delivered::add)).isZero();
        assertThat(sequencer.accept("t", 1, "a", delivered::add)).isEqualTo(3);
        assertThat(sequencer.accept("t", 1, "a again", delivered::add)).isZero();
        assertThat(sequencer.accept("u", 1, "x", delivered::add)).isEqualTo(1);
        assertThat(delivered).containsExactly("a", "b", "c", "x");

        sequencer.forget("u"::equals);
        assertThat(sequencer.accept("u", 1, "y", delivered::add))
                .as("a new stream")
                .isEqualTo(1);
    }

    @Test
    void headersCarryVersionEpochsIdAndDeadline() {
        Header header = Headers.create(new Headers.Epochs(3, 4), Duration.ofSeconds(60), () -> 1_000);
        assertThat(header.getProtocolVersion()).isEqualTo("1.0");
        assertThat(header.getLeaderEpoch()).isEqualTo(3);
        assertThat(header.getJobEpoch()).isEqualTo(4);
        assertThat(header.getDeadlineMs()).isEqualTo(61_000);
        assertThat(header.getRequestId()).hasSize(36).isNotEqualTo(header(3, 4).getRequestId());

        Headers.checkVersion(header.toBuilder().setProtocolVersion("1.7").build());
        for (String version : new String[] {"2.0", "", "one"}) {
            assertThatThrownBy(() -> Headers.checkVersion(
                            header.toBuilder().setProtocolVersion(version).build()))
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.code())
                            .isEqualTo(ErrorCode.RPC_VERSION_MISMATCH));
        }
    }
}
