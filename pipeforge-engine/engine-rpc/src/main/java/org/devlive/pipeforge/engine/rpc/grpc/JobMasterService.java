/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.ReaderMessage;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.devlive.pipeforge.engine.rpc.proto.Ack;
import org.devlive.pipeforge.engine.rpc.proto.Header;
import org.devlive.pipeforge.engine.rpc.proto.JobMasterServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.MetricsReport;
import org.devlive.pipeforge.engine.rpc.proto.ReaderEvent;

/**
 * The gRPC side of a job master: checks the protocol version, runs each request ID once, hands
 * reader events over in order and passes metrics on, then calls the job master's {@link
 * JobMasterGateway}.
 */
public final class JobMasterService extends JobMasterServiceGrpc.JobMasterServiceImplBase {

    private record Stream(ExecutionAttemptId task, String node) {}

    private final JobMasterGateway target;
    private final IdempotencyCache requests;
    private final Sequencer<Stream, ReaderMessage> events = new Sequencer<>();

    public JobMasterService(JobMasterGateway target, IdempotencyCache requests) {
        this.target = target;
        this.requests = requests;
    }

    @Override
    public void updateTaskState(
            org.devlive.pipeforge.engine.rpc.proto.TaskStateUpdate request, StreamObserver<Ack> observer) {
        handle(observer, request.getHeader(), () -> {
            TaskStateUpdate update = ProtoMapping.fromProto(request);
            if (update.state().isTerminal()) {
                events.forget(stream -> stream.task().equals(update.id()));
            }
            return target.updateTaskState(update);
        });
    }

    @Override
    public void sendReaderEvent(ReaderEvent request, StreamObserver<Ack> observer) {
        handle(observer, request.getHeader(), () -> {
            ReaderMessage message = ProtoMapping.fromProto(request);
            List<CompletableFuture<Void>> delivered = new ArrayList<>();
            events.accept(
                    new Stream(message.task(), message.node()),
                    request.getSequence(),
                    message,
                    m -> delivered.add(target.sendReaderMessage(m)));
            return CompletableFuture.allOf(delivered.toArray(CompletableFuture[]::new));
        });
    }

    @Override
    public StreamObserver<MetricsReport> reportMetrics(StreamObserver<Ack> observer) {
        return new StreamObserver<>() {
            private boolean checked;

            @Override
            @SuppressWarnings("FutureReturnValueIgnored") // the callback completes another future
            public void onNext(MetricsReport report) {
                if (!checked) {
                    try {
                        Headers.checkVersion(report.getHeader());
                    } catch (RuntimeException e) {
                        observer.onError(RpcErrors.toStatus(e));
                        return;
                    }
                    checked = true;
                }
                target.reportMetrics(
                        ProtoMapping.fromProto(report.getTask()), ProtoMapping.fromProto(report.getMetrics()));
            }

            @Override
            public void onError(Throwable error) {
                // the worker opens a new stream; the next report holds the full counters again
            }

            @Override
            public void onCompleted() {
                observer.onNext(Ack.getDefaultInstance());
                observer.onCompleted();
            }
        };
    }

    private void handle(StreamObserver<Ack> observer, Header header, Supplier<CompletableFuture<?>> work) {
        CompletableFuture<?> result;
        try {
            Headers.checkVersion(header);
            result = requests.run(header.getRequestId(), () -> {
                try {
                    return work.get().thenApply(ignored -> null);
                } catch (RuntimeException e) {
                    return CompletableFuture.failedFuture(e);
                }
            });
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }
        GrpcTransport.respond(observer, result);
    }
}
