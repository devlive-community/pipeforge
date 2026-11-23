/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.devlive.pipeforge.engine.rpc.proto.Ack;
import org.devlive.pipeforge.engine.rpc.proto.HeartbeatAck;
import org.devlive.pipeforge.engine.rpc.proto.RegisterWorkerRequest;
import org.devlive.pipeforge.engine.rpc.proto.RegisterWorkerResponse;
import org.devlive.pipeforge.engine.rpc.proto.ResourceManagerServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.SlotReport;
import org.devlive.pipeforge.engine.rpc.proto.WorkerHeartbeat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The worker's lease runs from when the answered heartbeat was sent, not from when its answer
 * arrived: a slow answer must not let the worker run its tasks past the moment the master counts it
 * as lost.
 */
class MasterLeaseTest {

    /** A master that registers at once and answers heartbeats only when the test says. */
    static final class SlowMaster extends ResourceManagerServiceGrpc.ResourceManagerServiceImplBase {
        final List<StreamObserver<HeartbeatAck>> streams = new CopyOnWriteArrayList<>();
        final AtomicLong beats = new AtomicLong();

        @Override
        public void registerWorker(RegisterWorkerRequest request, StreamObserver<RegisterWorkerResponse> observer) {
            observer.onNext(RegisterWorkerResponse.newBuilder()
                    .setWorkerId("w-1")
                    .setHeartbeatIntervalMs(1_000)
                    .setHeartbeatTimeoutMs(30_000)
                    .build());
            observer.onCompleted();
        }

        @Override
        public StreamObserver<WorkerHeartbeat> heartbeat(StreamObserver<HeartbeatAck> acks) {
            streams.add(acks);
            return new StreamObserver<>() {
                @Override
                public void onNext(WorkerHeartbeat beat) {
                    beats.incrementAndGet();
                }

                @Override
                public void onError(Throwable error) {}

                @Override
                public void onCompleted() {
                    acks.onCompleted();
                }
            };
        }

        @Override
        public void offerSlots(SlotReport report, StreamObserver<Ack> observer) {
            observer.onNext(Ack.getDefaultInstance());
            observer.onCompleted();
        }

        void answerOne() {
            streams.get(streams.size() - 1).onNext(HeartbeatAck.getDefaultInstance());
        }
    }

    final String name = "lease-" + UUID.randomUUID();
    final SlowMaster master = new SlowMaster();
    final MasterConnectionTest.Tasks tasks = new MasterConnectionTest.Tasks();
    final AtomicLong now = new AtomicLong();
    Server server;
    ManagedChannel channel;
    MasterConnection connection;

    @AfterEach
    void stop() throws InterruptedException {
        connection.close();
        channel.shutdownNow();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void aSlowAnswerDoesNotExtendTheLease() throws Exception {
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(master)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        connection = new MasterConnection(
                channel,
                new MasterConnection.Config(
                        "worker-1:6124", "worker-1:6125", 1, Map.of(), "etl", Duration.ofMillis(20)),
                new byte[32],
                tasks,
                new SlotTable("worker-1:6124", 1),
                now::get);

        // registration is sent at 0 and answered at once; the first heartbeat goes out with it
        connection.tick();
        MasterConnectionTest.await("registration", () -> connection.state() == MasterConnection.State.CONNECTED);
        assertThat(master.beats).hasValue(1);

        // the network is slow: the answer to the heartbeat sent at 0 arrives at 20 s
        now.set(TimeUnit.SECONDS.toNanos(20));
        connection.tick();
        master.answerOne();

        now.set(TimeUnit.SECONDS.toNanos(30) - 1);
        connection.tick();
        assertThat(connection.state()).isEqualTo(MasterConnection.State.CONNECTED);
        assertThat(tasks.cancelled).hasValue(0);

        // 30 s after the answered heartbeat was sent the master may count the worker as lost
        now.set(TimeUnit.SECONDS.toNanos(30));
        connection.tick();
        assertThat(connection.state()).isEqualTo(MasterConnection.State.LOST);
        assertThat(tasks.cancelled)
                .as("fenced, though an answer arrived only 10 s ago")
                .hasValue(1);
    }
}
