/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.proto.ErrorInfo;
import org.jspecify.annotations.Nullable;

/**
 * Errors across the RPC: a refused or failed call carries a gRPC status and, in the trailers, the
 * {@link ErrorInfo} of the error, so the caller rebuilds the same {@link PipeForgeException}.
 */
public final class RpcErrors {

    /** The trailer that carries the error. */
    public static final Metadata.Key<ErrorInfo> ERROR = ProtoUtils.keyForProto(ErrorInfo.getDefaultInstance());

    private RpcErrors() {}

    /** The status a refusal or failure is sent with. */
    static Status.Code code(PipeForgeException error) {
        ErrorCode code = error.code();
        if (code.equals(ErrorCode.RPC_UNAUTHENTICATED)) {
            return Status.Code.UNAUTHENTICATED;
        }
        if (code.equals(ErrorCode.RPC_VERSION_MISMATCH)) {
            return Status.Code.UNIMPLEMENTED;
        }
        if (code.equals(ErrorCode.RPC_STALE_EPOCH)) {
            return Status.Code.FAILED_PRECONDITION;
        }
        return Status.Code.INTERNAL;
    }

    /** {@code error} as a status with its {@link ErrorInfo} in the trailers. */
    public static StatusRuntimeException toStatus(Throwable error) {
        PipeForgeException failure = PipeForgeException.wrap(unwrap(error));
        Metadata trailers = new Metadata();
        trailers.put(ERROR, ProtoMapping.toProto(failure));
        return Status.fromCode(code(failure))
                .withDescription(failure.getMessage())
                .asRuntimeException(trailers);
    }

    /**
     * The error a call to {@code target} ended with: the error the other side sent, or {@link
     * ErrorCode#RPC_UNAVAILABLE} when it could not answer.
     */
    public static PipeForgeException fromStatus(Throwable error, String target) {
        Throwable cause = unwrap(error);
        if (cause instanceof PipeForgeException e) {
            return e;
        }
        if (cause instanceof StatusRuntimeException status) {
            Metadata trailers = status.getTrailers();
            ErrorInfo info = trailers == null ? null : trailers.get(ERROR);
            if (info != null) {
                return ProtoMapping.fromProto(info);
            }
            return PipeForgeException.of(ErrorCode.RPC_UNAVAILABLE)
                    .param("target", target)
                    .param("reason", describe(status.getStatus()))
                    .cause(status)
                    .build();
        }
        return PipeForgeException.wrap(cause);
    }

    static String describe(Status status) {
        String text = status.getDescription();
        return status.getCode() + (text == null ? "" : ": " + text);
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Whether a failed call may be sent again: the other side did not answer. */
    static boolean retryable(@Nullable Throwable error) {
        return error != null
                && unwrap(error) instanceof StatusRuntimeException status
                && (status.getStatus().getCode() == Status.Code.UNAVAILABLE
                        || status.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED)
                && (status.getTrailers() == null || status.getTrailers().get(ERROR) == null);
    }
}
