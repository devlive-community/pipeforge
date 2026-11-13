/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.common.error.RemoteCause;
import org.devlive.pipeforge.engine.rpc.proto.ErrorInfo;
import org.devlive.pipeforge.engine.rpc.proto.OperatorMetrics;
import org.devlive.pipeforge.engine.rpc.proto.TaskMetrics;
import org.devlive.pipeforge.engine.rpc.proto.Versioned;

/**
 * Converts between the engine's own types and the messages of protocol v1 (package {@code
 * pipeforge.rpc.v1}). The engine works with its own types; only the gRPC transport sees the
 * messages.
 */
public final class ProtoMapping {

    /** The characters a vertex ID may use, so that the text form of an attempt ID stays unambiguous. */
    public static final Pattern VERTEX_ID = Pattern.compile("[A-Za-z0-9_.-]+");

    private ProtoMapping() {}

    // ----------------------------------------------------------------------------- attempts

    /**
     * The attempt as a message.
     *
     * @throws IllegalArgumentException if the vertex ID has characters other than {@link #VERTEX_ID}
     */
    public static org.devlive.pipeforge.engine.rpc.proto.ExecutionAttemptId toProto(ExecutionAttemptId id) {
        if (!VERTEX_ID.matcher(id.vertexId()).matches()) {
            throw new IllegalArgumentException("Vertex ID '" + id.vertexId() + "' may only use " + VERTEX_ID);
        }
        return org.devlive.pipeforge.engine.rpc.proto.ExecutionAttemptId.newBuilder()
                .setRunId(id.runId())
                .setVertexId(id.vertexId())
                .setSubtask(id.subtask())
                .setAttempt(id.attempt())
                .build();
    }

    public static ExecutionAttemptId fromProto(org.devlive.pipeforge.engine.rpc.proto.ExecutionAttemptId id) {
        return new ExecutionAttemptId(id.getRunId(), id.getVertexId(), id.getSubtask(), id.getAttempt());
    }

    // ----------------------------------------------------------------------------- task state

    public static org.devlive.pipeforge.engine.rpc.proto.TaskState toProto(TaskState state) {
        return switch (state) {
            case DEPLOYING -> org.devlive.pipeforge.engine.rpc.proto.TaskState.TASK_STATE_DEPLOYING;
            case RUNNING -> org.devlive.pipeforge.engine.rpc.proto.TaskState.TASK_STATE_RUNNING;
            case FINISHED -> org.devlive.pipeforge.engine.rpc.proto.TaskState.TASK_STATE_FINISHED;
            case FAILED -> org.devlive.pipeforge.engine.rpc.proto.TaskState.TASK_STATE_FAILED;
            case CANCELED -> org.devlive.pipeforge.engine.rpc.proto.TaskState.TASK_STATE_CANCELED;
        };
    }

    /**
     * The state of a message.
     *
     * @throws IllegalArgumentException for an unset or unknown state (from a newer minor version)
     */
    public static TaskState fromProto(org.devlive.pipeforge.engine.rpc.proto.TaskState state) {
        return switch (state) {
            case TASK_STATE_DEPLOYING -> TaskState.DEPLOYING;
            case TASK_STATE_RUNNING -> TaskState.RUNNING;
            case TASK_STATE_FINISHED -> TaskState.FINISHED;
            case TASK_STATE_FAILED -> TaskState.FAILED;
            case TASK_STATE_CANCELED -> TaskState.CANCELED;
            case TASK_STATE_UNSPECIFIED, UNRECOGNIZED -> throw new IllegalArgumentException(
                    "Unknown task state " + state);
        };
    }

    // ----------------------------------------------------------------------------- plugin values

    public static Versioned toProto(SerializedValue value) {
        return Versioned.newBuilder()
                .setVersion(value.version())
                .setData(com.google.protobuf.ByteString.copyFrom(value.bytes()))
                .build();
    }

    public static SerializedValue fromProto(Versioned value) {
        return new SerializedValue(value.getVersion(), value.getData().toByteArray());
    }

    // ----------------------------------------------------------------------------- settings

    /** The settings as a message; limits this engine version does not set yet are 0 (the default). */
    public static org.devlive.pipeforge.engine.rpc.proto.TaskSettings toProto(TaskSettings settings) {
        return org.devlive.pipeforge.engine.rpc.proto.TaskSettings.newBuilder()
                .setAsyncWriteBuffer(settings.asyncWriteBuffer())
                .setWriteBatchSize(settings.writeBatchSize())
                .setWriteQueueCapacity(settings.writeQueueCapacity())
                .setCancelTimeoutMs(settings.cancelTimeout().toMillis())
                .setMetricsIntervalMs(settings.metricsInterval().toMillis())
                .build();
    }

    public static TaskSettings fromProto(org.devlive.pipeforge.engine.rpc.proto.TaskSettings settings) {
        return new TaskSettings(
                settings.getAsyncWriteBuffer(),
                settings.getWriteBatchSize(),
                settings.getWriteQueueCapacity(),
                Duration.ofMillis(settings.getCancelTimeoutMs()),
                Duration.ofMillis(settings.getMetricsIntervalMs()));
    }

    // ----------------------------------------------------------------------------- metrics

    public static TaskMetrics toProto(TaskMetricsSnapshot metrics) {
        TaskMetrics.Builder builder = TaskMetrics.newBuilder()
                .setRunningNanos(metrics.runningNanos())
                .setIdleNanos(metrics.idleNanos())
                .setReaderWaitNanos(metrics.readerWaitNanos())
                .setWriterWaitNanos(metrics.writerWaitNanos());
        for (OperatorSnapshot operator : metrics.operators()) {
            builder.addOperators(OperatorMetrics.newBuilder()
                    .setNodeId(operator.node())
                    .setRowsIn(operator.rowsIn())
                    .setRowsOut(operator.rowsOut())
                    .setRowsFiltered(operator.rowsFiltered())
                    .setRowsExpanded(operator.rowsExpanded())
                    .setSelfTimeNanos(operator.selfTimeNanos()));
        }
        return builder.build();
    }

    public static TaskMetricsSnapshot fromProto(TaskMetrics metrics) {
        List<OperatorSnapshot> operators = new ArrayList<>(metrics.getOperatorsCount());
        for (OperatorMetrics operator : metrics.getOperatorsList()) {
            operators.add(new OperatorSnapshot(
                    operator.getNodeId(),
                    operator.getRowsIn(),
                    operator.getRowsOut(),
                    operator.getRowsFiltered(),
                    operator.getRowsExpanded(),
                    operator.getSelfTimeNanos()));
        }
        return new TaskMetricsSnapshot(
                operators,
                metrics.getRunningNanos(),
                metrics.getIdleNanos(),
                metrics.getReaderWaitNanos(),
                metrics.getWriterWaitNanos());
    }

    // ----------------------------------------------------------------------------- errors

    /** The error as a message: code, name, parameters as text, scope, kind, English text and causes. */
    public static ErrorInfo toProto(PipeForgeException error) {
        ErrorInfo.Builder builder = ErrorInfo.newBuilder()
                .setCode(error.code().id())
                .setName(error.code().name())
                .setMessage(error.localizedMessage("en-US"))
                .setKind(error.kind().name())
                .addAllCauses(PipeForgeException.causeChain(error));
        error.params().forEach((name, value) -> builder.putParams(name, String.valueOf(value)));
        error.scope().toMap().forEach((name, value) -> builder.putScope(name, String.valueOf(value)));
        return builder.build();
    }

    /**
     * The error of a message, with its causes as {@link RemoteCause}s. Parameters arrive as text, in
     * the order of their names.
     *
     * @throws IllegalArgumentException if the code or name is malformed
     */
    public static PipeForgeException fromProto(ErrorInfo error) {
        Map<String, String> params = new TreeMap<>(error.getParamsMap());
        ErrorCode code = ErrorCode.define(
                error.getCode(), error.getName(), params.keySet().toArray(String[]::new));
        PipeForgeException.Builder builder = PipeForgeException.of(code)
                .scope(ErrorScope.fromMap(error.getScopeMap()))
                .cause(RemoteCause.chain(error.getCausesList()));
        params.forEach(builder::param);
        if (!error.getKind().isEmpty()) {
            builder.kind(ErrorKind.valueOf(error.getKind()));
        }
        return builder.build();
    }
}
