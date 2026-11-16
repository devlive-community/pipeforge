/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.devlive.pipeforge.engine.network.Exchange.codec;
import static org.devlive.pipeforge.engine.network.Exchange.drain;
import static org.devlive.pipeforge.engine.network.Exchange.partition;
import static org.devlive.pipeforge.engine.network.Exchange.produce;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.devlive.pipeforge.engine.network.gate.InputGate;
import org.devlive.pipeforge.engine.network.partition.ResultPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tasks on the same worker: buffers handed over in memory, with credit, order and backpressure. */
class LocalExchangeTest {

    NetworkEnvironment worker = start(Exchange.options());

    static NetworkEnvironment start(NetworkOptions options) {
        NetworkEnvironment environment = new NetworkEnvironment("worker-1", "token", options);
        environment.start("127.0.0.1", 0);
        return environment;
    }

    @AfterEach
    void stop() {
        worker.close();
    }

    InputGate gate(ResultPartitionId id, int... subpartitions) {
        List<InputGate.ChannelSpec> specs = new ArrayList<>();
        for (int subpartition : subpartitions) {
            specs.add(new InputGate.ChannelSpec(id, subpartition, null));
        }
        return worker.createInputGate("consumer of " + id, specs);
    }

    static CompletableFuture<Exchange.Received> read(InputGate input) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return drain(input, codec(), Duration.ZERO);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                },
                Exchange.THREADS);
    }

    @Test
    void rowsAndEventsArriveInTheirOrder() throws Exception {
        ResultPartitionId id = partition("source");
        ResultPartition output = worker.createPartition(id, 2, codec());
        InputGate first = gate(id, 0);
        InputGate second = gate(id, 1);
        CompletableFuture<Exchange.Received> evens = read(first);
        CompletableFuture<Exchange.Received> odds = read(second);
        produce(output, 0, 5000, false).get(30, TimeUnit.SECONDS);
        NetworkEvent change = new NetworkEvent.SchemaChange(new byte[] {8, 1});
        output.emitEvent(0, change);
        produce(output, 5000, 5000, false).get(30, TimeUnit.SECONDS);
        output.broadcastEvent(NetworkEvent.END_OF_INPUT_EVENT);
        output.finish();
        assertThat(output.isFinished()).isTrue();
        assertThatThrownBy(() -> output.emit(0, Exchange.row(1), null, () -> false))
                .isInstanceOf(IllegalStateException.class);

        Exchange.Received even = evens.get(30, TimeUnit.SECONDS);
        Exchange.Received odd = odds.get(30, TimeUnit.SECONDS);

        assertThat(even.ids().get(0)).isEqualTo(Exchange.every(0, 2, 10_000));
        assertThat(odd.ids().get(0)).isEqualTo(Exchange.every(1, 2, 10_000));
        // the schema change stands between the rows written before and after it, the end of input last
        int at = even.stream().indexOf(change);
        assertThat(even.stream().get(at - 1)).isEqualTo(4998L);
        assertThat(even.stream().get(at + 1)).isEqualTo(5000L);
        assertThat(even.stream()).last().isEqualTo(NetworkEvent.END_OF_INPUT_EVENT);
        assertThat(odd.stream()).doesNotContain(change).last().isEqualTo(NetworkEvent.END_OF_INPUT_EVENT);
        assertThat(first.next(Duration.ofMillis(10))).as("an ended input").isNull();

        Exchange.await(() -> worker.partitions().size() == 0, "the output is released once read");
        assertThat(output.isReleased()).isTrue();
        assertThat(output.metrics().rows()).isEqualTo(10_000);
        assertThat(output.metrics().buffers()).isGreaterThan(10);
        assertThat(first.metrics().buffers()).isGreaterThan(5);
        first.close();
        second.close();
        assertThat(worker.pool().availableBuffers()).isEqualTo(worker.pool().totalBuffers());
    }

    @Test
    void aBufferThatIsNotFullIsSentAfterTheBufferTimeout() throws Exception {
        ResultPartitionId id = partition("slow-source");
        ResultPartition output = worker.createPartition(id, 1, codec());
        try (InputGate input = gate(id, 0)) {
            long started = System.nanoTime();
            output.emit(0, Exchange.row(42), 1234L, () -> false);
            InputGate.Next next = input.next(Duration.ofSeconds(5));
            assertThat(next).isNotNull();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isLessThan(2000);
            assertThat(codec().decodeAll(next.buffer().content()))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.row().get(0)).isEqualTo(42L);
                        assertThat(row.eventTime()).isEqualTo(1234L);
                    });
            next.buffer().recycle();
        }
    }

    @Test
    void aConsumerHoldsNoMoreBuffersThanItsCreditAndTheProducerWaits() throws Exception {
        worker.close();
        worker = start(Exchange.options().withChannels(2, 1));
        ResultPartitionId id = partition("fast-source");
        ResultPartition output = worker.createPartition(id, 1, codec());
        InputGate input = gate(id, 0);
        CompletableFuture<Void> producer = produce(output, 0, 50_000, true);

        List<Buffer> held = new ArrayList<>();
        Exchange.await(
                () -> {
                    try {
                        InputGate.Next next = input.next(Duration.ofMillis(10));
                        if (next != null) {
                            held.add(next.buffer());
                        }
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return held.size() == 3;
                },
                "the consumer takes its credit");
        assertThat(input.next(Duration.ofMillis(200)))
                .as("no buffer beyond 2 own and 1 floating")
                .isNull();
        assertThat(input.metrics().credits()).containsExactly(0);
        Exchange.await(() -> output.metrics().backpressuredMs() > 100, "the producer waits for buffers");
        assertThat(producer).isNotDone();
        assertThat(output.metrics().bufferUsage()).isEqualTo(1.0);

        held.forEach(Buffer::recycle);
        Exchange.Received rest = drain(input, codec(), Duration.ZERO);
        producer.get(30, TimeUnit.SECONDS);
        assertThat(rest.count() + held.size()).isGreaterThan(0);
        input.close();
    }

    @Test
    void anOutputRequestedBeforeItExistsIsServedOnceItAppears() throws Exception {
        ResultPartitionId id = partition("late");
        InputGate input = gate(id, 0);
        Thread.sleep(200);
        ResultPartition output = worker.createPartition(id, 1, codec());
        produce(output, 0, 100, true).get(30, TimeUnit.SECONDS);
        assertThat(drain(input, codec(), Duration.ZERO).ids().get(0)).isEqualTo(Exchange.every(0, 1, 100));
        input.close();

        try (InputGate missing = gate(partition("never"), 0)) {
            assertThatThrownBy(() -> drain(missing, codec(), Duration.ZERO))
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.PARTITION_NOT_FOUND);
                        assertThat(e.params())
                                .containsEntry("partition", "run-1/never/0/0#0")
                                .containsEntry("worker", "worker-1")
                                .containsEntry("seconds", 2L);
                    });
        }
        ResultPartitionId twice = partition("twice");
        worker.createPartition(twice, 1, codec());
        assertThatThrownBy(() -> worker.createPartition(twice, 1, codec())).hasMessageContaining("exists already");
    }

    @Test
    void aRowLargerThanABufferHasOneOfItsOwnAndTooLargeRowsFail() throws Exception {
        ResultPartitionId id = partition("wide");
        ResultPartition output = worker.createPartition(id, 1, codec());
        InputGate input = gate(id, 0);
        output.emit(0, Exchange.row(1), null, () -> false);
        output.emit(0, GenericRow.of(Exchange.TABLE, 2L, "x".repeat(10_000)), null, () -> false);
        output.emit(0, Exchange.row(3), null, () -> false);
        assertThatThrownBy(
                        () -> output.emit(0, GenericRow.of(Exchange.TABLE, 4L, "x".repeat(70_000)), null, () -> false))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.ROW_TOO_LARGE);
                    assertThat(e.params()).containsEntry("table", "orders").containsEntry("limit", 65536);
                });
        output.finish();
        assertThat(drain(input, codec(), Duration.ZERO).ids().get(0)).containsExactly(1L, 2L, 3L);
        input.close();
    }

    @Test
    void aConsumerThatStopsEarlyLetsTheProducerFinish() throws Exception {
        ResultPartitionId id = partition("abandoned");
        ResultPartition output = worker.createPartition(id, 1, codec());
        InputGate input = gate(id, 0);
        CompletableFuture<Void> producer = produce(output, 0, 100_000, true);
        InputGate.Next first = input.next(Duration.ofSeconds(5));
        assertThat(first).isNotNull();
        first.buffer().recycle();
        input.close();
        assertThat(input.next(Duration.ofMillis(10))).isNull();
        producer.get(30, TimeUnit.SECONDS);
        Exchange.await(() -> worker.partitions().size() == 0, "the output is released");
        Exchange.await(() -> worker.pool().availableBuffers() == worker.pool().totalBuffers(), "all buffers come back");
    }

    @Test
    void optionsAreChecked() {
        NetworkOptions defaults = NetworkOptions.defaults();
        assertThat(defaults.totalBuffers()).isEqualTo(2048);
        assertThat(defaults.maxFrameBytes()).isEqualTo((64 << 20) + 1024);
        assertThatThrownBy(() -> defaults.withMemory(100_000, 100)).hasMessageContaining("network.buffer-size");
        assertThatThrownBy(() -> defaults.withMemory(1000, 1024)).hasMessageContaining("worker.memory.network");
        assertThatThrownBy(() -> defaults.withChannels(0, 1)).hasMessageContaining("buffers-per-channel");
        assertThatThrownBy(() -> defaults.withChannels(1, -1)).hasMessageContaining("floating-buffers-per-gate");
        assertThatThrownBy(() -> defaults.withMaxRowBytes(10)).hasMessageContaining("task.max-row-bytes");
        assertThatThrownBy(() -> defaults.withTimeouts(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new ResultPartitionId("r", "v", -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> worker.createInputGate("none", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NetworkEvent.fromBuffer(Buffer.unpooled(Buffer.Kind.EVENT, new byte[] {0x11})))
                .hasMessage("unknown event type 0x11");
        assertThatThrownBy(() -> NetworkEvent.fromBuffer(Buffer.unpooled(Buffer.Kind.EVENT, new byte[0])))
                .hasMessage("an empty event");
        assertThatThrownBy(() -> NetworkEvent.fromBuffer(Buffer.unpooled(Buffer.Kind.EVENT, new byte[] {1, 0})))
                .hasMessageContaining("1 extra bytes");
        assertThatThrownBy(() -> NetworkEvent.fromBuffer(Buffer.unpooled(Buffer.Kind.EVENT, new byte[] {3, 5, 1})))
                .hasMessageContaining("a schema change of 5 bytes in 1");
        NetworkEvent change = new NetworkEvent.SchemaChange(new byte[] {1, 2});
        assertThat(NetworkEvent.fromBuffer(NetworkEvent.toBuffer(change)))
                .isEqualTo(change)
                .hasSameHashCodeAs(change);
        assertThat(change).hasToString("SchemaChange[2 bytes]");
    }
}
