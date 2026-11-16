/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.buffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class BufferPoolTest {

    @Test
    void reservesBuffersAndRefusesWhatItDoesNotHave() {
        NetworkBufferPool network = new NetworkBufferPool(10, 1024);
        LocalBufferPool first = network.createLocalPool("input a", 6, 6);
        assertThat(network.availableBuffers()).isEqualTo(4);
        assertThatThrownBy(() -> network.createLocalPool("input b", 5, 5))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NETWORK_BUFFERS_INSUFFICIENT);
                    assertThat(e.getMessage())
                            .isEqualTo("PF-RES-0008 input b needs 5 network buffers, but only 4 of 10 are free");
                });
        first.destroy();
        assertThat(network.availableBuffers()).isEqualTo(10);
        assertThatThrownBy(first::poll).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new NetworkBufferPool(0, 1024)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void growsToItsMaximumAndGivesTheExtraBuffersBack() {
        NetworkBufferPool network = new NetworkBufferPool(10, 1024);
        LocalBufferPool pool = network.createLocalPool("output", 2, 4);
        Buffer[] taken = new Buffer[4];
        for (int i = 0; i < 4; i++) {
            taken[i] = pool.poll();
            assertThat(taken[i]).isNotNull();
            assertThat(taken[i].capacity()).isEqualTo(1024);
        }
        assertThat(pool.poll()).as("at its maximum").isNull();
        assertThat(pool.usage()).isEqualTo(1.0);
        assertThat(network.availableBuffers()).isEqualTo(6);
        for (Buffer buffer : taken) {
            buffer.recycle();
            buffer.recycle();
        }
        assertThat(pool.buffersInUse()).isZero();
        assertThat(network.availableBuffers())
                .as("the two above the reservation went back")
                .isEqualTo(8);
        assertThat(network.createdBuffers()).as("memory is reused").isEqualTo(4);
        Buffer again = pool.poll();
        assertThat(network.createdBuffers()).isEqualTo(4);
        again.recycle();
    }

    @Test
    void takingWaitsForARecycledBuffer() throws Exception {
        NetworkBufferPool network = new NetworkBufferPool(1, 1024);
        LocalBufferPool pool = network.createLocalPool("output", 1, 1);
        Buffer held = pool.take(() -> false);
        CompletableFuture<Buffer> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return pool.take(() -> false);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(100);
        assertThat(waiting).isNotDone();
        held.recycle();
        Buffer next = waiting.get(5, TimeUnit.SECONDS);
        assertThat(pool.waitNanos()).isGreaterThan(TimeUnit.MILLISECONDS.toNanos(50));

        assertThatThrownBy(() -> pool.take(() -> true)).isInstanceOf(InterruptedException.class);
        CompletableFuture<Buffer> failing = CompletableFuture.supplyAsync(() -> {
            try {
                return pool.take(() -> false);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(50);
        PipeForgeException error = PipeForgeException.of(ErrorCode.DATA_CONNECTION_LOST)
                .param("peer", "w2")
                .param("reason", "test")
                .build();
        pool.fail(error);
        assertThatThrownBy(() -> failing.get(5, TimeUnit.SECONDS)).hasCause(error);
        next.recycle();
    }

    @Test
    void buffersWithoutAPoolAndViews() {
        Buffer event = Buffer.unpooled(Buffer.Kind.EVENT, new byte[] {1, 2});
        assertThat(event.isEvent()).isTrue();
        assertThat(event.size()).isEqualTo(2);
        assertThat(event.content().remaining()).isEqualTo(2);
        int[] calls = new int[1];
        Buffer view = event.onRecycle(() -> calls[0]++);
        assertThat(view.memory()).isSameAs(event.memory());
        view.recycle();
        view.recycle();
        assertThat(calls[0]).isEqualTo(1);
        assertThat(event.isRecycled()).isTrue();
        assertThatThrownBy(() -> event.setSize(3)).isInstanceOf(IllegalArgumentException.class);
    }
}
