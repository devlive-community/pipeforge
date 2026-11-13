/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorInfo;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.proto.TaskMetrics;
import org.devlive.pipeforge.engine.rpc.proto.Versioned;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every mapping survives a trip through the bytes of protocol v1. */
class ProtoMappingTest {

    @Test
    void attemptIds() throws IOException {
        ExecutionAttemptId id = new ExecutionAttemptId("6f1c-run", "src_1.chain-0", 3, 2);
        var bytes = ProtoMapping.toProto(id).toByteArray();
        assertThat(ProtoMapping.fromProto(org.devlive.pipeforge.engine.rpc.proto.ExecutionAttemptId.parseFrom(bytes)))
                .isEqualTo(id);
    }

    @Test
    void vertexIdsCannotBreakTheTextForm() {
        assertThatThrownBy(() -> ProtoMapping.toProto(new ExecutionAttemptId("r", "a/b", 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a/b");
        assertThatThrownBy(() -> ProtoMapping.toProto(new ExecutionAttemptId("r", "", 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(TaskState.class)
    void taskStates(TaskState state) {
        assertThat(ProtoMapping.fromProto(ProtoMapping.toProto(state))).isEqualTo(state);
    }

    @Test
    void unknownTaskStatesAreRejected() {
        assertThatThrownBy(() ->
                        ProtoMapping.fromProto(org.devlive.pipeforge.engine.rpc.proto.TaskState.TASK_STATE_UNSPECIFIED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtoMapping.fromProto(org.devlive.pipeforge.engine.rpc.proto.TaskState.UNRECOGNIZED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pluginValuesKeepTheirSerializerVersion() throws IOException {
        SerializedValue value = new SerializedValue(7, new byte[] {0, 1, (byte) 0xff});
        var bytes = ProtoMapping.toProto(value).toByteArray();
        assertThat(ProtoMapping.fromProto(Versioned.parseFrom(bytes))).isEqualTo(value);
    }

    @Test
    void taskSettings() throws IOException {
        TaskSettings settings = new TaskSettings(false, 512, 8, Duration.ofSeconds(45), Duration.ofMillis(250));
        var proto = ProtoMapping.toProto(settings);
        assertThat(proto.getCancelKillTimeoutMs())
                .as("not set by this engine version")
                .isZero();
        assertThat(ProtoMapping.fromProto(
                        org.devlive.pipeforge.engine.rpc.proto.TaskSettings.parseFrom(proto.toByteArray())))
                .isEqualTo(settings);
    }

    @Test
    void taskMetrics() throws IOException {
        TaskMetricsSnapshot metrics = new TaskMetricsSnapshot(
                List.of(
                        new OperatorSnapshot("src", 0, 100, 0, 0, 5_000),
                        new OperatorSnapshot("map", 100, 98, 4, 2, 7_000),
                        new OperatorSnapshot("out", 98, 90, 0, 0, 9_000)),
                1_000_000,
                20_000,
                300,
                4_000);
        var bytes = ProtoMapping.toProto(metrics).toByteArray();
        assertThat(ProtoMapping.fromProto(TaskMetrics.parseFrom(bytes))).isEqualTo(metrics);
        assertThat(ProtoMapping.fromProto(TaskMetrics.getDefaultInstance())).isEqualTo(TaskMetricsSnapshot.EMPTY);
    }

    @Test
    void errorsKeepCodeParametersScopeKindAndCauses() throws IOException {
        PipeForgeException error = PipeForgeException.of(ErrorCode.OPTION_INVALID)
                .param("option", "rows")
                .param("node", "src")
                .param("value", -5)
                .param("reason", "must not be negative")
                .scope(ErrorScope.node("src").withRun("r1").withSubtask(2))
                .kind(ErrorKind.FATAL)
                .cause(new IllegalStateException("outer", new IOException("connection reset")))
                .build();

        var bytes = ProtoMapping.toProto(error).toByteArray();
        PipeForgeException rebuilt =
                ProtoMapping.fromProto(org.devlive.pipeforge.engine.rpc.proto.ErrorInfo.parseFrom(bytes));

        assertThat(rebuilt.code()).isEqualTo(ErrorCode.OPTION_INVALID);
        assertThat(rebuilt.code().name()).isEqualTo("OPTION_INVALID");
        assertThat(rebuilt.scope()).isEqualTo(error.scope());
        assertThat(rebuilt.kind()).isEqualTo(ErrorKind.FATAL);
        ErrorInfo before = error.toErrorInfo("en-US");
        ErrorInfo after = rebuilt.toErrorInfo("en-US");
        assertThat(after.message()).isEqualTo(before.message()).contains("rows").contains("-5");
        assertThat(after.hint()).isEqualTo(before.hint());
        assertThat(after.causeChain())
                .isEqualTo(before.causeChain())
                .containsExactly("java.lang.IllegalStateException: outer", "java.io.IOException: connection reset");
        assertThat(after.scope()).isEqualTo(before.scope());
    }

    @Test
    void errorsOfCodesThisProcessDoesNotKnowStillArrive() {
        var info = org.devlive.pipeforge.engine.rpc.proto.ErrorInfo.newBuilder()
                .setCode("PF-PLUG-0999")
                .setName("SOMETHING_NEW")
                .putParams("what", "x")
                .setMessage("PF-PLUG-0999 Something new: x")
                .build();
        PipeForgeException rebuilt = ProtoMapping.fromProto(info);
        assertThat(rebuilt.code().id()).isEqualTo("PF-PLUG-0999");
        assertThat(rebuilt.params()).containsEntry("what", "x");
        assertThat(rebuilt.scope()).isEqualTo(ErrorScope.none());
        assertThat(PipeForgeException.causeChain(rebuilt)).isEmpty();
    }
}
