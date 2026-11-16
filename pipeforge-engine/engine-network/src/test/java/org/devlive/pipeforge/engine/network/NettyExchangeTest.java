/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.devlive.pipeforge.engine.network.Exchange.codec;
import static org.devlive.pipeforge.engine.network.Exchange.drain;
import static org.devlive.pipeforge.engine.network.Exchange.partition;
import static org.devlive.pipeforge.engine.network.Exchange.produce;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.devlive.pipeforge.engine.network.gate.InputGate;
import org.devlive.pipeforge.engine.network.partition.ResultPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Two workers in one process exchanging rows over TCP. */
class NettyExchangeTest {

    final List<NetworkEnvironment> workers = new ArrayList<>();

    NetworkEnvironment worker(String id, String token, NetworkOptions options) {
        NetworkEnvironment environment = new NetworkEnvironment(id, token, options);
        environment.start("127.0.0.1", 0);
        workers.add(environment);
        return environment;
    }

    NetworkEnvironment worker(String id) {
        return worker(id, "token", Exchange.options());
    }

    @AfterEach
    void stop() {
        workers.forEach(NetworkEnvironment::close);
    }

    static InputGate remote(
            NetworkEnvironment consumer, NetworkEnvironment producer, ResultPartitionId id, int... subs) {
        List<InputGate.ChannelSpec> specs = new ArrayList<>();
        for (int sub : subs) {
            specs.add(new InputGate.ChannelSpec(id, sub, producer.address()));
        }
        return consumer.createInputGate("consumer of " + id, specs);
    }

    static CompletableFuture<Exchange.Received> read(InputGate input, Duration pause) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return drain(input, codec(), pause);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                },
                Exchange.THREADS);
    }

    static PipeForgeException failure(CompletableFuture<?> future) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> future.get(30, TimeUnit.SECONDS));
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        Throwable cause = thrown.getCause();
        assertThat(cause).isInstanceOf(PipeForgeException.class);
        return (PipeForgeException) cause;
    }

    @Test
    void transfersRowsBetweenTwoWorkersOverOneConnection() throws Exception {
        NetworkEnvironment upstream = worker("worker-a");
        NetworkEnvironment downstream = worker("worker-b");
        ResultPartitionId id = partition("source");
        ResultPartition output = upstream.createPartition(id, 3, codec());
        List<CompletableFuture<Exchange.Received>> consumers = new ArrayList<>();
        List<InputGate> inputs = new ArrayList<>();
        for (int sub = 0; sub < 3; sub++) {
            InputGate input = remote(downstream, upstream, id, sub);
            inputs.add(input);
            consumers.add(read(input, Duration.ZERO));
        }
        produce(output, 0, 150_000, false).get(60, TimeUnit.SECONDS);
        output.emitEvent(1, new NetworkEvent.SchemaChange(new byte[] {7}));
        output.broadcastEvent(NetworkEvent.END_OF_INPUT_EVENT);
        output.finish();

        for (int sub = 0; sub < 3; sub++) {
            Exchange.Received received = consumers.get(sub).get(60, TimeUnit.SECONDS);
            assertThat(received.ids().get(0)).isEqualTo(Exchange.every(sub, 3, 150_000));
            assertThat(received.stream()).last().isEqualTo(NetworkEvent.END_OF_INPUT_EVENT);
            assertThat(received.stream().contains(new NetworkEvent.SchemaChange(new byte[] {7})))
                    .isEqualTo(sub == 1);
        }
        assertThat(downstream.transport().openConnections())
                .as("one connection for three inputs")
                .isEqualTo(1);
        assertThat(inputs.get(0).metrics().bytes()).isGreaterThan(100_000);
        Exchange.await(() -> upstream.partitions().size() == 0, "the output is released once read");
        inputs.forEach(InputGate::close);
        Exchange.await(
                () -> downstream.pool().availableBuffers() == downstream.pool().totalBuffers(),
                "the inputs give their buffers back");
    }

    @Test
    void aConsumerThatStopsReadingHoldsBackTheProducer() throws Exception {
        NetworkOptions options = Exchange.options().withChannels(2, 2);
        NetworkEnvironment upstream = worker("worker-a", "token", options);
        NetworkEnvironment downstream = worker("worker-b", "token", options);
        ResultPartitionId id = partition("source");
        ResultPartition output = upstream.createPartition(id, 1, codec());
        InputGate input = remote(downstream, upstream, id, 0);
        CompletableFuture<Void> producer = produce(output, 0, 100_000, true);

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
                    return input.metrics().credits().get(0) == 0;
                },
                "the consumer uses up its credit");
        // take what arrived on the credit already given, until nothing more comes
        for (InputGate.Next next = input.next(Duration.ofMillis(300));
                next != null;
                next = input.next(Duration.ofMillis(300))) {
            held.add(next.buffer());
        }
        Exchange.await(() -> output.metrics().backpressuredMs() > 200, "the producer waits");
        assertThat(producer).isNotDone();
        assertThat(held).as("at most 2 own and 2 floating buffers").hasSizeBetween(2, 4);
        assertThat(input.metrics().credits()).containsExactly(0);

        held.forEach(Buffer::recycle);
        Exchange.Received rest = drain(input, codec(), Duration.ZERO);
        producer.get(60, TimeUnit.SECONDS);
        assertThat(rest.count()).isGreaterThan(90_000);
        input.close();
    }

    @Test
    void aSlowConsumerDoesNotStopAFastOneOnTheSameConnection() throws Exception {
        NetworkEnvironment upstream = worker("worker-a");
        NetworkEnvironment downstream = worker("worker-b");
        ResultPartitionId slowId = partition("slow");
        ResultPartitionId fastId = partition("fast");
        ResultPartition slowOutput = upstream.createPartition(slowId, 1, codec());
        ResultPartition fastOutput = upstream.createPartition(fastId, 1, codec());
        InputGate slow = remote(downstream, upstream, slowId, 0);
        InputGate fast = remote(downstream, upstream, fastId, 0);
        CompletableFuture<Void> slowProducer = produce(slowOutput, 0, 100_000, true);
        CompletableFuture<Void> fastProducer = produce(fastOutput, 0, 50_000, true);
        CompletableFuture<Exchange.Received> slowReader = read(slow, Duration.ofMillis(20));

        Exchange.Received received = drain(fast, codec(), Duration.ZERO);
        fastProducer.get(30, TimeUnit.SECONDS);
        assertThat(received.count()).isEqualTo(50_000);
        assertThat(slowProducer).as("the slow one is still held back").isNotDone();
        assertThat(slowReader).isNotDone();
        assertThat(downstream.transport().openConnections()).isEqualTo(1);

        slow.close();
        slowProducer.get(30, TimeUnit.SECONDS);
        fast.close();
    }

    @Test
    void aLostConnectionFailsTheTasksOnBothEnds() throws Exception {
        NetworkEnvironment upstream = worker("worker-a");
        NetworkEnvironment downstream = worker("worker-b");
        ResultPartitionId id = partition("source");
        ResultPartition output = upstream.createPartition(id, 1, codec());
        InputGate input = remote(downstream, upstream, id, 0);
        CompletableFuture<Void> producer = produce(output, 0, Long.MAX_VALUE / 2, true);
        CompletableFuture<Exchange.Received> consumer = read(input, Duration.ofMillis(1));
        Exchange.await(() -> input.metrics().buffers() > 10, "rows arrive");

        downstream.transport().abort(upstream.address());

        PipeForgeException consumerError = failure(consumer);
        assertThat(consumerError.code()).isEqualTo(ErrorCode.DATA_CONNECTION_LOST);
        assertThat(consumerError.params().get("peer").toString()).startsWith("worker-a (");
        PipeForgeException producerError = failure(producer);
        assertThat(producerError.code()).isEqualTo(ErrorCode.DATA_CONNECTION_LOST);
        assertThat(producerError.params().get("peer").toString()).startsWith("worker-b (");
        assertThat(producerError.params()).containsEntry("reason", "the connection of the consumer closed");
        input.close();
    }

    @Test
    void aConnectionThatTheProducerLosesFailsBothEndsToo() throws Exception {
        NetworkEnvironment upstream = worker("worker-a");
        NetworkEnvironment downstream = worker("worker-b");
        ResultPartitionId id = partition("source");
        ResultPartition output = upstream.createPartition(id, 1, codec());
        InputGate input = remote(downstream, upstream, id, 0);
        CompletableFuture<Void> producer = produce(output, 0, Long.MAX_VALUE / 2, true);
        CompletableFuture<Exchange.Received> consumer = read(input, Duration.ofMillis(1));
        Exchange.await(() -> input.metrics().buffers() > 10, "rows arrive");

        upstream.transport().abortAccepted();

        assertThat(failure(consumer).code()).isEqualTo(ErrorCode.DATA_CONNECTION_LOST);
        assertThat(failure(producer).code()).isEqualTo(ErrorCode.DATA_CONNECTION_LOST);
        input.close();
        // a new connection is opened for the next input
        ResultPartitionId next = partition("next");
        ResultPartition again = upstream.createPartition(next, 1, codec());
        InputGate reader = remote(downstream, upstream, next, 0);
        produce(again, 0, 10, true).get(30, TimeUnit.SECONDS);
        assertThat(drain(reader, codec(), Duration.ZERO).count()).isEqualTo(10);
        reader.close();
    }

    @Test
    void refusesWorkersWithAnotherTokenAndOutputsThatDoNotExist() throws Exception {
        NetworkEnvironment upstream = worker("worker-a");
        NetworkEnvironment stranger = worker("worker-x", "other", Exchange.options());
        try (InputGate refused = remote(stranger, upstream, partition("source"), 0)) {
            PipeForgeException error = failure(read(refused, Duration.ZERO));
            assertThat(error.code()).isEqualTo(ErrorCode.RPC_UNAUTHENTICATED);
        }
        NetworkEnvironment downstream = worker("worker-b");
        try (InputGate missing = remote(downstream, upstream, partition("never"), 0)) {
            PipeForgeException error = failure(read(missing, Duration.ZERO));
            assertThat(error.code()).isEqualTo(ErrorCode.PARTITION_NOT_FOUND);
            assertThat(error.params())
                    .containsEntry("partition", "run-1/never/0/0#0")
                    .containsEntry("seconds", 2L);
            assertThat(error.params().get("worker").toString()).startsWith("worker-a (");
        }
        NetworkEnvironment nobody = new NetworkEnvironment("worker-z", "token", Exchange.options());
        try (InputGate unreachable = downstream.createInputGate(
                "unreachable",
                List.of(new InputGate.ChannelSpec(partition("x"), 0, new InetSocketAddress("127.0.0.1", 1))))) {
            PipeForgeException error = failure(read(unreachable, Duration.ZERO));
            assertThat(error.code()).isEqualTo(ErrorCode.DATA_CONNECTION_LOST);
            assertThat(error.params().get("reason").toString()).startsWith("cannot connect");
        } finally {
            nobody.close();
        }
    }

    static byte[] frame(io.netty.buffer.ByteBuf payload, int type) {
        ByteBuf out = Unpooled.buffer();
        out.writeShort(0x5046)
                .writeByte(1)
                .writeByte(type)
                .writeInt(payload.readableBytes())
                .writeBytes(payload);
        return ByteBufUtil.getBytes(out);
    }

    static byte[] hello(String token) {
        ByteBuf payload = Unpooled.buffer();
        for (String text : new String[] {"1.0", "probe", token}) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            payload.writeByte(bytes.length).writeBytes(bytes);
        }
        return frame(payload, 0);
    }

    @Test
    void closesConnectionsThatStaySilentOrBreakTheProtocol() throws Exception {
        NetworkEnvironment upstream = worker(
                "worker-a",
                "token",
                Exchange.options().withTimeouts(Duration.ofMillis(20), Duration.ofMillis(200), Duration.ofSeconds(2)));
        InetSocketAddress address = upstream.address();
        try (Socket silent = new Socket(address.getAddress(), address.getPort())) {
            silent.setSoTimeout(10_000);
            OutputStream out = silent.getOutputStream();
            out.write(hello("token"));
            out.flush();
            long started = System.nanoTime();
            InputStream in = silent.getInputStream();
            int pings = 0;
            byte[] header = new byte[8];
            while (true) {
                int read = in.readNBytes(header, 0, 8);
                if (read < 8) {
                    break;
                }
                int length = ((header[4] & 0xFF) << 24)
                        | ((header[5] & 0xFF) << 16)
                        | ((header[6] & 0xFF) << 8)
                        | (header[7] & 0xFF);
                in.skipNBytes(length);
                if (header[3] == 0x07) {
                    pings++;
                }
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(millis)
                    .as("closed after three intervals without a frame")
                    .isBetween(500L, 5000L);
            assertThat(pings).as("the server pinged while it waited").isGreaterThanOrEqualTo(1);
        }
        try (Socket garbage = new Socket(address.getAddress(), address.getPort())) {
            garbage.setSoTimeout(10_000);
            garbage.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            byte[] answer = garbage.getInputStream().readAllBytes();
            assertThat(answer[3]).as("an error response").isEqualTo((byte) 0x06);
            assertThat(new String(answer, StandardCharsets.UTF_8))
                    .contains("PF-SYS-0001")
                    .contains("not 0x5046");
        }
        try (Socket early = new Socket(address.getAddress(), address.getPort())) {
            early.setSoTimeout(10_000);
            early.getOutputStream().write(frame(Unpooled.buffer(), 7));
            assertThat(new String(early.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("a Ping before Hello");
        }
        try (Socket wrong = new Socket(address.getAddress(), address.getPort())) {
            wrong.setSoTimeout(10_000);
            wrong.getOutputStream().write(hello("guess"));
            assertThat(new String(wrong.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("PF-CLUS-0005");
        }
    }
}
