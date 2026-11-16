/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class AsyncWriteBufferTest {

    /** A target that blocks every write until released. */
    private static final class GatedTarget implements AsyncWriteBuffer.Target {
        final List<Object> written = new CopyOnWriteArrayList<>();
        final List<String> flushes = new CopyOnWriteArrayList<>();
        final CountDownLatch open = new CountDownLatch(1);
        final CountDownLatch writing = new CountDownLatch(1);
        volatile String failOn = "";

        @Override
        public void write(Object item) {
            writing.countDown();
            try {
                open.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (item.equals(failOn)) {
                throw PipeForgeException.of(ErrorCode.SINK_FAILED)
                        .param("node", "out")
                        .param("reason", "bad " + item)
                        .build();
            }
            written.add(item);
        }

        @Override
        public void flush() {
            flushes.add("after " + written.size());
        }
    }

    @Test
    void blocksTheProducerWhileTheUnwrittenRowsReachTheMemoryLimit() throws Exception {
        GatedTarget target = new GatedTarget();
        // batches of 10 and a long queue: only the 250-byte limit can hold the producer back
        try (AsyncWriteBuffer buffer =
                new AsyncWriteBuffer("w-test", target, 10, 100, 250, getClass().getClassLoader())) {
            buffer.add("a", 100);
            buffer.add("b", 100);
            assertThat(buffer.accountedBytes()).isEqualTo(200);
            CountDownLatch added = new CountDownLatch(1);
            Thread producer = new Thread(() -> {
                buffer.add("c", 100);
                added.countDown();
            });
            producer.start();

            // the waiting producer hands the current batch over, so the writer can make room
            assertThat(target.writing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(added.await(200, TimeUnit.MILLISECONDS)).isFalse();
            target.open.countDown();
            assertThat(added.await(5, TimeUnit.SECONDS)).isTrue();
            producer.join();
            assertThat(buffer.accountedBytes()).isEqualTo(100);
            buffer.add("huge", 10_000);
            buffer.finish();

            assertThat(target.written).containsExactly("a", "b", "c", "huge");
            assertThat(buffer.accountedBytes()).as("all written").isZero();
            assertThat(buffer.producerWaitNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(150));
        }
    }

    @Test
    void closingDropsWhatWasNotWritten() throws Exception {
        GatedTarget target = new GatedTarget();
        AsyncWriteBuffer buffer =
                new AsyncWriteBuffer("w-test", target, 1, 4, 1_000, getClass().getClassLoader());
        buffer.add("a", 100);
        assertThat(target.writing.await(5, TimeUnit.SECONDS)).isTrue();
        buffer.add("b", 100);
        assertThat(buffer.accountedBytes()).isEqualTo(200);
        buffer.close();
        assertThat(buffer.accountedBytes()).isZero();
    }

    @Test
    void blocksTheProducerWhileTheQueueIsFull() throws Exception {
        GatedTarget target = new GatedTarget();
        try (AsyncWriteBuffer buffer =
                new AsyncWriteBuffer("w-test", target, 1, 2, getClass().getClassLoader())) {
            // The write thread takes the first batch and blocks in the target; two more fill the queue.
            buffer.add("a");
            assertThat(target.writing.await(5, TimeUnit.SECONDS)).isTrue();
            buffer.add("b");
            buffer.add("c");
            CountDownLatch added = new CountDownLatch(1);
            Thread producer = new Thread(() -> {
                buffer.add("d");
                added.countDown();
            });
            producer.start();

            assertThat(added.await(200, TimeUnit.MILLISECONDS)).isFalse();
            target.open.countDown();
            assertThat(added.await(5, TimeUnit.SECONDS)).isTrue();
            producer.join();
            buffer.finish();

            assertThat(target.written).containsExactly("a", "b", "c", "d");
            assertThat(buffer.producerWaitNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(150));
        }
    }

    @Test
    void flushWaitsUntilEverythingBeforeItIsWritten() {
        GatedTarget target = new GatedTarget();
        target.open.countDown();
        try (AsyncWriteBuffer buffer =
                new AsyncWriteBuffer("w-test", target, 10, 4, getClass().getClassLoader())) {
            buffer.add("a");
            buffer.add("b");
            buffer.flush();

            assertThat(target.written).containsExactly("a", "b");
            assertThat(target.flushes).containsExactly("after 2");
            buffer.add("c");
            buffer.finish();
            assertThat(target.written).containsExactly("a", "b", "c");
            assertThat(buffer.writerWaitNanos()).isPositive();
        }
    }

    @Test
    void rethrowsAWriterFailureAndNeverBlocksAfterIt() {
        GatedTarget target = new GatedTarget();
        target.failOn = "b";
        target.open.countDown();
        try (AsyncWriteBuffer buffer =
                new AsyncWriteBuffer("w-test", target, 1, 1, getClass().getClassLoader())) {
            assertThatThrownBy(() -> {
                        for (int i = 0; i < 1000; i++) {
                            buffer.add(i == 0 ? "a" : "b");
                        }
                        buffer.finish();
                    })
                    .isInstanceOf(PipeForgeException.class)
                    .hasMessageContaining("bad b");
            assertThatThrownBy(buffer::flush).hasMessageContaining("bad b");
        }
    }

    @Test
    void closeStopsAWriterThatIsStillWaiting() throws Exception {
        GatedTarget target = new GatedTarget();
        AsyncWriteBuffer buffer =
                new AsyncWriteBuffer("w-test", target, 1, 1, getClass().getClassLoader());
        buffer.add("a");
        assertThat(target.writing.await(5, TimeUnit.SECONDS)).isTrue();
        buffer.close();

        assertThatThrownBy(() -> buffer.add("b")).isInstanceOf(PipeForgeException.class);
    }

    @Test
    void runsTheWriterWithThePluginClassLoader() throws Exception {
        ClassLoader plugin = new ClassLoader(getClass().getClassLoader()) {};
        CountDownLatch seen = new CountDownLatch(1);
        List<ClassLoader> loaders = new CopyOnWriteArrayList<>();
        AsyncWriteBuffer.Target target = new AsyncWriteBuffer.Target() {
            @Override
            public void write(Object item) {
                loaders.add(Thread.currentThread().getContextClassLoader());
                seen.countDown();
            }

            @Override
            public void flush() {}
        };
        try (AsyncWriteBuffer buffer = new AsyncWriteBuffer("w-test", target, 1, 1, plugin)) {
            buffer.add("a");
            buffer.finish();
        }
        assertThat(loaders).containsExactly(plugin);
    }
}
