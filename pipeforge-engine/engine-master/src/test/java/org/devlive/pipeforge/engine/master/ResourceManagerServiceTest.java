/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.rpc.grpc.ClusterToken;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.grpc.RpcErrors;
import org.devlive.pipeforge.engine.rpc.proto.Header;
import org.devlive.pipeforge.engine.rpc.proto.HeartbeatAck;
import org.devlive.pipeforge.engine.rpc.proto.RegisterWorkerRequest;
import org.devlive.pipeforge.engine.rpc.proto.ResourceManagerServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.ResourceProfile;
import org.devlive.pipeforge.engine.rpc.proto.RunningTask;
import org.devlive.pipeforge.engine.rpc.proto.SlotReport;
import org.devlive.pipeforge.engine.rpc.proto.SlotStatus;
import org.devlive.pipeforge.engine.rpc.proto.WorkerHeartbeat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Workers registering, beating and offering slots over gRPC, as a worker process does. */
class ResourceManagerServiceTest {

    static final ClusterToken TOKEN = new ClusterToken("cluster-secret");

    final String name = "rm-" + UUID.randomUUID();
    final ResourceManager manager = new ResourceManager(ResourceManager.Settings.DEFAULTS);
    Server server;
    ManagedChannel channel;
    ResourceManagerServiceGrpc.ResourceManagerServiceBlockingStub blocking;

    @BeforeEach
    void start() throws Exception {
        server = InProcessServerBuilder.forName(name)
                .intercept(TOKEN.server())
                .addService(new ResourceManagerService(manager))
                .build()
                .start();
        channel =
                InProcessChannelBuilder.forName(name).intercept(TOKEN.client()).build();
        blocking = ResourceManagerServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() throws InterruptedException {
        channel.shutdownNow();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    static Header header() {
        return Headers.create(Headers.Epochs.FIRST, Duration.ofSeconds(5), System::currentTimeMillis);
    }

    static RegisterWorkerRequest registration(String previous) {
        return RegisterWorkerRequest.newBuilder()
                .setHeader(header())
                .setEngineVersion("0.1.0")
                .setRpcAddress("worker-1:6124")
                .setDataAddress("worker-1:6125")
                .setResources(ResourceProfile.newBuilder().setCpuCores(4).setTaskHeapBytes(1L << 30))
                .setSlots(2)
                .putLabels("zone", "east")
                .setPublicKey(ByteString.copyFrom(new byte[32]))
                .setPreviousWorkerId(previous)
                .build();
    }

    @Test
    void aWorkerRegistersAndOffersItsSlots() {
        var response = blocking.registerWorker(registration(""));
        assertThat(response.getLeaderEpoch()).isEqualTo(1);
        assertThat(response.getHeartbeatIntervalMs()).isEqualTo(5_000);
        assertThat(response.getHeartbeatTimeoutMs()).isEqualTo(30_000);
        assertThat(response.getProtocolVersion()).isEqualTo("1.0");

        blocking.offerSlots(SlotReport.newBuilder()
                .setHeader(header())
                .setWorkerId(response.getWorkerId())
                .addSlots(SlotStatus.newBuilder()
                        .setIndex(0)
                        .setAllocated(true)
                        .setJobId("job")
                        .setAllocationId("a"))
                .addSlots(SlotStatus.newBuilder().setIndex(1))
                .build());
        assertThat(manager.workers()).singleElement().satisfies(w -> {
            assertThat(w.id()).isEqualTo(response.getWorkerId());
            assertThat(w.spec().rpcAddress()).isEqualTo("worker-1:6124");
            assertThat(w.spec().resources().cpuCores()).isEqualTo(4);
            assertThat(w.slots())
                    .containsExactly(new ResourceManager.Slot(0, true, "job", "a"), ResourceManager.Slot.free(1));
        });
        assertThat(blocking.registerWorker(registration(response.getWorkerId())).getWorkerId())
                .isEqualTo(response.getWorkerId());
    }

    @Test
    void heartbeatsTravelOverOneStream() throws Exception {
        String id = blocking.registerWorker(registration("")).getWorkerId();
        LinkedBlockingQueue<HeartbeatAck> acks = new LinkedBlockingQueue<>();
        StreamObserver<WorkerHeartbeat> beats = ResourceManagerServiceGrpc.newStub(channel)
                .heartbeat(new StreamObserver<>() {
                    @Override
                    public void onNext(HeartbeatAck ack) {
                        acks.add(ack);
                    }

                    @Override
                    public void onError(Throwable error) {}

                    @Override
                    public void onCompleted() {}
                });
        ExecutionAttemptId task = new ExecutionAttemptId("run", "src", 0, 0);
        beats.onNext(WorkerHeartbeat.newBuilder()
                .setHeader(header())
                .setWorkerId(id)
                .addTasks(RunningTask.newBuilder()
                        .setId(ProtoMapping.toProto(task))
                        .setState(ProtoMapping.toProto(TaskState.RUNNING)))
                .build());
        beats.onNext(WorkerHeartbeat.newBuilder().setWorkerId("unknown").build());

        HeartbeatAck first = acks.poll(5, TimeUnit.SECONDS);
        HeartbeatAck second = acks.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(first.getReregister()).isFalse();
        assertThat(first.getLeaderEpoch()).isEqualTo(1);
        assertThat(second).isNotNull();
        assertThat(second.getReregister()).isTrue();
        assertThat(manager.workers().get(0).tasks())
                .containsExactly(new ResourceManager.RunningTask(task, TaskState.RUNNING));
        beats.onCompleted();
    }

    @Test
    void anotherMajorVersionCannotRegister() {
        var request = registration("").toBuilder()
                .setHeader(header().toBuilder().setProtocolVersion("2.0"))
                .build();
        assertThatThrownBy(() -> blocking.registerWorker(request))
                .satisfies(e ->
                        assertThat(RpcErrors.fromStatus(e, "master").code()).isEqualTo(ErrorCode.RPC_VERSION_MISMATCH));
        assertThat(manager.workers()).isEmpty();
    }

    @Test
    void aWorkerWithoutSlotsIsRefused() {
        var request = registration("").toBuilder().setSlots(0).build();
        assertThatThrownBy(() -> blocking.registerWorker(request))
                .satisfies(e -> assertThat(RpcErrors.fromStatus(e, "master").getMessage())
                        .contains("at least one slot"));
        assertThat(manager.workers()).isEmpty();
    }

    @Test
    void slotsOfAnUnknownWorkerAreIgnored() {
        blocking.offerSlots(SlotReport.newBuilder()
                .setHeader(header())
                .setWorkerId("unknown")
                .addAllSlots(List.of(SlotStatus.newBuilder().setIndex(0).build()))
                .build());
        assertThat(manager.workers()).isEmpty();
    }
}
