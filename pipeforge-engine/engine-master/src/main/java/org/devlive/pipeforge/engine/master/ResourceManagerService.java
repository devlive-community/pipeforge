/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.ProtocolVersion;
import org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.grpc.RpcErrors;
import org.devlive.pipeforge.engine.rpc.proto.Ack;
import org.devlive.pipeforge.engine.rpc.proto.HeartbeatAck;
import org.devlive.pipeforge.engine.rpc.proto.RegisterWorkerRequest;
import org.devlive.pipeforge.engine.rpc.proto.RegisterWorkerResponse;
import org.devlive.pipeforge.engine.rpc.proto.ResourceManagerServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.SlotReport;
import org.devlive.pipeforge.engine.rpc.proto.SlotStatus;
import org.devlive.pipeforge.engine.rpc.proto.WorkerHeartbeat;

/**
 * The gRPC side of the {@link ResourceManager}: workers register, keep a heartbeat stream open and
 * report their slots (protocol specification, section 2.3).
 */
public final class ResourceManagerService extends ResourceManagerServiceGrpc.ResourceManagerServiceImplBase {

    private final ResourceManager manager;

    public ResourceManagerService(ResourceManager manager) {
        this.manager = manager;
    }

    @Override
    public void registerWorker(RegisterWorkerRequest request, StreamObserver<RegisterWorkerResponse> observer) {
        ResourceManager.Registration registration;
        try {
            Headers.checkVersion(request.getHeader());
            var resources = request.getResources();
            registration = manager.register(new ResourceManager.WorkerSpec(
                    request.getEngineVersion(),
                    request.getRpcAddress(),
                    request.getDataAddress(),
                    new ResourceManager.Resources(
                            resources.getCpuCores(),
                            resources.getTaskHeapBytes(),
                            resources.getNetworkBytes(),
                            resources.getManagedBytes()),
                    request.getSlots(),
                    request.getLabelsMap(),
                    request.getResourceGroup(),
                    request.getPublicKey().toByteArray(),
                    request.getPreviousWorkerId().isEmpty() ? null : request.getPreviousWorkerId()));
        } catch (RuntimeException e) {
            observer.onError(RpcErrors.toStatus(e));
            return;
        }
        ProtocolVersion remote = ProtocolVersion.parse(request.getHeader().getProtocolVersion());
        observer.onNext(RegisterWorkerResponse.newBuilder()
                .setWorkerId(registration.workerId())
                .setLeaderEpoch(registration.leaderEpoch())
                .setHeartbeatIntervalMs(registration.heartbeatInterval().toMillis())
                .setHeartbeatTimeoutMs(registration.heartbeatTimeout().toMillis())
                .setProtocolVersion(
                        ProtocolVersion.CURRENT.negotiate(remote).orElseThrow().toString())
                .build());
        observer.onCompleted();
    }

    @Override
    public StreamObserver<WorkerHeartbeat> heartbeat(StreamObserver<HeartbeatAck> observer) {
        return new StreamObserver<>() {
            private boolean checked;

            @Override
            public void onNext(WorkerHeartbeat beat) {
                ResourceManager.HeartbeatAck ack;
                try {
                    if (!checked) {
                        Headers.checkVersion(beat.getHeader());
                        checked = true;
                    }
                    List<ResourceManager.RunningTask> tasks = new ArrayList<>();
                    beat.getTasksList()
                            .forEach(t -> tasks.add(new ResourceManager.RunningTask(
                                    ProtoMapping.fromProto(t.getId()), ProtoMapping.fromProto(t.getState()))));
                    ack = manager.heartbeat(beat.getWorkerId(), slots(beat.getSlotsList()), tasks);
                } catch (RuntimeException e) {
                    observer.onError(RpcErrors.toStatus(e));
                    return;
                }
                observer.onNext(HeartbeatAck.newBuilder()
                        .setLeaderEpoch(ack.leaderEpoch())
                        .setReregister(ack.reregister())
                        .build());
            }

            @Override
            public void onError(Throwable error) {
                // a worker that is gone stops sending heartbeats; expiry removes it
            }

            @Override
            public void onCompleted() {
                observer.onCompleted();
            }
        };
    }

    @Override
    public void offerSlots(SlotReport request, StreamObserver<Ack> observer) {
        CompletableFuture<Void> result;
        try {
            Headers.checkVersion(request.getHeader());
            manager.offerSlots(request.getWorkerId(), slots(request.getSlotsList()));
            result = CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }
        GrpcTransport.respond(observer, result);
    }

    static List<ResourceManager.Slot> slots(List<SlotStatus> reported) {
        List<ResourceManager.Slot> slots = new ArrayList<>(reported.size());
        for (SlotStatus slot : reported) {
            slots.add(new ResourceManager.Slot(
                    slot.getIndex(),
                    slot.getAllocated(),
                    slot.getJobId().isEmpty() ? null : slot.getJobId(),
                    slot.getAllocationId().isEmpty() ? null : slot.getAllocationId()));
        }
        return slots;
    }
}
