/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.ReaderMessage;
import org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.devlive.pipeforge.engine.rpc.proto.Ack;
import org.devlive.pipeforge.engine.rpc.proto.JobMasterServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.MetricsReport;
import org.jspecify.annotations.Nullable;

/**
 * The job master as the tasks of a worker call it over gRPC: the same {@link JobMasterGateway} as
 * in process. Reader events are numbered per task and node; metrics go over one client stream,
 * opened again after an error, since every report holds the full counters.
 */
public final class JobMasterClient implements JobMasterGateway, AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private record Stream(ExecutionAttemptId task, String node) {}

    private final JobMasterServiceGrpc.JobMasterServiceFutureStub stub;
    private final JobMasterServiceGrpc.JobMasterServiceStub streams;
    private final String target;
    private final Retry retry;
    private final Map<Stream, AtomicLong> sequences = new ConcurrentHashMap<>();
    private @Nullable StreamObserver<MetricsReport> metrics;

    /**
     * @param target how errors name the job master, e.g. its address
     */
    public JobMasterClient(Channel channel, String target, Retry retry) {
        this.stub = JobMasterServiceGrpc.newFutureStub(channel);
        this.streams = JobMasterServiceGrpc.newStub(channel);
        this.target = target;
        this.retry = retry;
    }

    @Override
    public CompletableFuture<Void> updateTaskState(TaskStateUpdate update) {
        var request = ProtoMapping.toProto(update).setHeader(header()).build();
        return call(() -> stub.withDeadlineAfter(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .updateTaskState(request));
    }

    @Override
    public CompletableFuture<Void> sendReaderMessage(ReaderMessage message) {
        org.devlive.pipeforge.engine.rpc.proto.ReaderEvent request;
        try {
            long sequence = sequences
                    .computeIfAbsent(new Stream(message.task(), message.node()), s -> new AtomicLong())
                    .incrementAndGet();
            request = ProtoMapping.toProto(message)
                    .setHeader(header())
                    .setSequence(sequence)
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(PipeForgeException.wrap(e));
        }
        return call(() -> stub.withDeadlineAfter(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .sendReaderEvent(request));
    }

    @Override
    public synchronized CompletableFuture<Void> reportMetrics(ExecutionAttemptId task, TaskMetricsSnapshot snapshot) {
        var report = MetricsReport.newBuilder()
                .setTask(ProtoMapping.toProto(task))
                .setTimestampMs(System.currentTimeMillis())
                .setMetrics(ProtoMapping.toProto(snapshot));
        if (metrics == null) {
            metrics = streams.reportMetrics(new StreamObserver<>() {
                @Override
                public void onNext(Ack value) {}

                @Override
                public void onError(Throwable error) {
                    reopen();
                }

                @Override
                public void onCompleted() {}
            });
            report.setHeader(header());
        }
        metrics.onNext(report.build());
        return CompletableFuture.completedFuture(null);
    }

    private synchronized void reopen() {
        metrics = null;
    }

    /** Ends the metrics stream. */
    @Override
    public synchronized void close() {
        if (metrics != null) {
            metrics.onCompleted();
            metrics = null;
        }
    }

    private org.devlive.pipeforge.engine.rpc.proto.Header header() {
        // tasks do not carry epochs: fencing protects the workers from replaced masters
        return Headers.create(new Headers.Epochs(0, 0), TIMEOUT, System::currentTimeMillis);
    }

    @SuppressWarnings("FutureReturnValueIgnored") // the callback completes another future
    private CompletableFuture<Void> call(Supplier<ListenableFuture<Ack>> send) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        retry.call(() -> GrpcTransport.completable(send.get())).whenComplete((ack, error) -> {
            if (error != null) {
                result.completeExceptionally(RpcErrors.fromStatus(error, target));
            } else {
                result.complete(null);
            }
        });
        return result;
    }
}
