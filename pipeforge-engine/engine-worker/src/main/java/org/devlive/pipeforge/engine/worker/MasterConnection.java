/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.proto.HeartbeatAck;
import org.devlive.pipeforge.engine.rpc.proto.RegisterWorkerRequest;
import org.devlive.pipeforge.engine.rpc.proto.RegisterWorkerResponse;
import org.devlive.pipeforge.engine.rpc.proto.ResourceManagerServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.ResourceProfile;
import org.devlive.pipeforge.engine.rpc.proto.RunningTask;
import org.devlive.pipeforge.engine.rpc.proto.SlotReport;
import org.devlive.pipeforge.engine.rpc.proto.SlotStatus;
import org.devlive.pipeforge.engine.rpc.proto.WorkerHeartbeat;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A worker's connection to the master (protocol specification, section 2.3): it registers, offers
 * its slots and keeps a heartbeat stream with the state of its slots and tasks. A master that does
 * not know the worker asks it to register again. When no heartbeat is answered for the timeout the
 * master gave, the worker considers itself cut off, cancels all its tasks — only the current master
 * may control them — and keeps trying to register.
 */
public final class MasterConnection implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MasterConnection.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    /** Where the worker stands with the master. */
    public enum State {
        /** Not registered yet, or asked to register again. */
        REGISTERING,
        /** Registered, heartbeats are answered. */
        CONNECTED,
        /** Heartbeats went unanswered for the timeout; the tasks were cancelled. */
        LOST
    }

    /**
     * What the worker offers.
     *
     * @param rpcAddress where the master calls the worker, {@code host:port}
     * @param dataAddress where other workers fetch its data, {@code host:port}
     * @param slots how many tasks it runs at the same time ({@code worker.slots})
     * @param retryInterval how often it tries to register while it is not connected
     */
    public record Config(
            String rpcAddress,
            String dataAddress,
            int slots,
            Map<String, String> labels,
            String resourceGroup,
            Duration retryInterval) {

        public Config {
            Objects.requireNonNull(rpcAddress, "rpcAddress");
            Objects.requireNonNull(dataAddress, "dataAddress");
            if (slots < 1) {
                throw new IllegalArgumentException("worker.slots must be at least 1, not " + slots);
            }
            labels = Map.copyOf(labels);
        }

        /** One slot per core, no labels, the default resource group, a retry every second. */
        public static Config defaults(String rpcAddress, String dataAddress) {
            return new Config(
                    rpcAddress,
                    dataAddress,
                    Runtime.getRuntime().availableProcessors(),
                    Map.of(),
                    "default",
                    Duration.ofSeconds(1));
        }
    }

    private final ResourceManagerServiceGrpc.ResourceManagerServiceFutureStub calls;
    private final ResourceManagerServiceGrpc.ResourceManagerServiceStub streams;
    private final Config config;
    private final byte[] publicKey;
    private final LocalTasks tasks;
    private final LongSupplier nanoTime;

    private State state = State.REGISTERING;
    private @Nullable String workerId;
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    private Duration heartbeatTimeout = Duration.ofSeconds(30);
    private long lastAnswer;
    private long lastSent;
    private boolean registering;
    private @Nullable StreamObserver<WorkerHeartbeat> heartbeats;
    private @Nullable ScheduledFuture<?> ticks;
    private @Nullable ScheduledExecutorService scheduler;
    private long period;

    /**
     * @param publicKey the X25519 key that sensitive options are sealed for
     */
    public MasterConnection(Channel channel, Config config, byte[] publicKey, LocalTasks tasks) {
        this(channel, config, publicKey, tasks, System::nanoTime);
    }

    MasterConnection(Channel channel, Config config, byte[] publicKey, LocalTasks tasks, LongSupplier nanoTime) {
        this.calls = ResourceManagerServiceGrpc.newFutureStub(channel);
        this.streams = ResourceManagerServiceGrpc.newStub(channel);
        this.config = config;
        this.publicKey = publicKey.clone();
        this.tasks = tasks;
        this.nanoTime = nanoTime;
    }

    /** Starts registering, then the heartbeats, on {@code scheduler}. */
    public synchronized void start(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
        schedule();
    }

    /** Ticks often enough to register on time and to send every heartbeat on time. */
    private void schedule() {
        long wanted = Math.max(1, Math.min(config.retryInterval().toMillis(), heartbeatInterval.toMillis()));
        if (scheduler == null || (ticks != null && wanted == period)) {
            return;
        }
        if (ticks != null) {
            ticks.cancel(false);
        }
        period = wanted;
        ticks = scheduler.scheduleWithFixedDelay(this::tick, ticks == null ? 0 : wanted, wanted, TimeUnit.MILLISECONDS);
    }

    /** The version in the manifest of the engine jar, or {@code dev} when run from classes. */
    static String engineVersion() {
        String version = MasterConnection.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    public synchronized State state() {
        return state;
    }

    /** The ID the master gave, or {@code null} before the first registration. */
    public synchronized @Nullable String workerId() {
        return workerId;
    }

    /** One step: register, send a heartbeat when it is due, or notice that the master is gone. */
    synchronized void tick() {
        long now = nanoTime.getAsLong();
        if (state != State.CONNECTED) {
            register();
            return;
        }
        if (now - lastAnswer >= heartbeatTimeout.toNanos()) {
            lose(now);
            return;
        }
        if (now - lastSent >= heartbeatInterval.toNanos()) {
            heartbeat(now);
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored") // the callback records the outcome
    private void register() {
        if (registering) {
            return;
        }
        registering = true;
        var request = RegisterWorkerRequest.newBuilder()
                .setHeader(header())
                .setEngineVersion(engineVersion())
                .setRpcAddress(config.rpcAddress())
                .setDataAddress(config.dataAddress())
                .setResources(ResourceProfile.newBuilder()
                        .setCpuCores(Runtime.getRuntime().availableProcessors())
                        .setTaskHeapBytes(Runtime.getRuntime().maxMemory()))
                .setSlots(config.slots())
                .putAllLabels(config.labels())
                .setResourceGroup(config.resourceGroup())
                .setPublicKey(ByteString.copyFrom(publicKey))
                .setPreviousWorkerId(workerId == null ? "" : workerId)
                .build();
        GrpcTransport.completable(calls.withDeadlineAfter(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .registerWorker(request))
                .whenComplete((response, error) -> registered(response, error));
    }

    private synchronized void registered(@Nullable RegisterWorkerResponse response, @Nullable Throwable error) {
        registering = false;
        if (error != null || response == null) {
            LOG.debug("Registration with the master failed; trying again", error);
            return;
        }
        boolean again = workerId != null;
        workerId = response.getWorkerId();
        heartbeatInterval = Duration.ofMillis(response.getHeartbeatIntervalMs());
        heartbeatTimeout = Duration.ofMillis(response.getHeartbeatTimeoutMs());
        state = State.CONNECTED;
        lastAnswer = nanoTime.getAsLong();
        schedule();
        LOG.info(
                "{} with the master as worker {} with {} slot(s)",
                again ? "Registered again" : "Registered",
                workerId,
                config.slots());
        offerSlots();
        heartbeat(lastAnswer);
    }

    @SuppressWarnings("FutureReturnValueIgnored") // a lost offer is repeated by every heartbeat
    private void offerSlots() {
        var report = SlotReport.newBuilder().setHeader(header()).setWorkerId(Objects.requireNonNull(workerId));
        for (int i = 0; i < config.slots(); i++) {
            report.addSlots(SlotStatus.newBuilder().setIndex(i));
        }
        calls.withDeadlineAfter(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).offerSlots(report.build());
    }

    private void heartbeat(long now) {
        var beat = WorkerHeartbeat.newBuilder().setWorkerId(Objects.requireNonNull(workerId));
        for (int i = 0; i < config.slots(); i++) {
            beat.addSlots(SlotStatus.newBuilder().setIndex(i));
        }
        tasks.taskStates()
                .forEach((id, taskState) -> beat.addTasks(RunningTask.newBuilder()
                        .setId(ProtoMapping.toProto(id))
                        .setState(ProtoMapping.toProto(taskState))));
        if (heartbeats == null) {
            heartbeats = streams.heartbeat(new StreamObserver<>() {
                @Override
                public void onNext(HeartbeatAck ack) {
                    answered(ack);
                }

                @Override
                public void onError(Throwable error) {
                    streamEnded();
                }

                @Override
                public void onCompleted() {
                    streamEnded();
                }
            });
            beat.setHeader(header());
        }
        heartbeats.onNext(beat.build());
        lastSent = now;
    }

    private synchronized void answered(HeartbeatAck ack) {
        lastAnswer = nanoTime.getAsLong();
        if (ack.getReregister() && state == State.CONNECTED) {
            LOG.info("The master does not know worker {}; registering again", workerId);
            state = State.REGISTERING;
        }
    }

    private synchronized void streamEnded() {
        heartbeats = null;
    }

    private void lose(long now) {
        LOG.warn(
                "No heartbeat was answered for {} s; cancelling the tasks of this worker and registering again",
                TimeUnit.NANOSECONDS.toSeconds(now - lastAnswer));
        state = State.LOST;
        if (heartbeats != null) {
            heartbeats.onCompleted();
            heartbeats = null;
        }
        tasks.cancelAll();
    }

    private static org.devlive.pipeforge.engine.rpc.proto.Header header() {
        // workers do not carry epochs: fencing protects workers from replaced masters, not the reverse
        return Headers.create(new Headers.Epochs(0, 0), CALL_TIMEOUT, System::currentTimeMillis);
    }

    /** Stops the heartbeats; the master loses the worker after its timeout. */
    @Override
    public synchronized void close() {
        if (ticks != null) {
            ticks.cancel(false);
        }
        if (heartbeats != null) {
            heartbeats.onCompleted();
            heartbeats = null;
        }
    }
}
