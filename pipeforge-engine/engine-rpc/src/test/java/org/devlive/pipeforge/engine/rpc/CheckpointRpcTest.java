/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.engine.rpc.proto.AcknowledgeCheckpointRequest;
import org.devlive.pipeforge.engine.rpc.proto.CheckpointInfo;
import org.devlive.pipeforge.engine.rpc.proto.CheckpointNotification;
import org.devlive.pipeforge.engine.rpc.proto.DeclineCheckpointRequest;
import org.devlive.pipeforge.engine.rpc.proto.JobStatus;
import org.devlive.pipeforge.engine.rpc.proto.ListCheckpointsResponse;
import org.devlive.pipeforge.engine.rpc.proto.RunState;
import org.devlive.pipeforge.engine.rpc.proto.SavepointRequest;
import org.devlive.pipeforge.engine.rpc.proto.SavepointResponse;
import org.devlive.pipeforge.engine.rpc.proto.SubmitJobRequest;
import org.devlive.pipeforge.engine.rpc.proto.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.proto.TriggerCheckpointRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The checkpoint messages of protocol v1 (M2): the wire, and the mapping to the engine's types. */
class CheckpointRpcTest {

    /** Every message the checkpoint RPCs added, and the messages that grew fields for them. */
    static final List<Message> MESSAGES = List.of(
            TriggerCheckpointRequest.getDefaultInstance(),
            CheckpointNotification.getDefaultInstance(),
            AcknowledgeCheckpointRequest.getDefaultInstance(),
            DeclineCheckpointRequest.getDefaultInstance(),
            SavepointRequest.getDefaultInstance(),
            SavepointResponse.getDefaultInstance(),
            ListCheckpointsResponse.getDefaultInstance(),
            CheckpointInfo.getDefaultInstance(),
            TaskDeployment.getDefaultInstance(),
            SubmitJobRequest.getDefaultInstance(),
            JobStatus.getDefaultInstance());

    /** A message with every field set, through every branch of its oneofs in turn. */
    static List<Message> filled(Descriptor type, int depth) {
        List<Map<OneofDescriptor, FieldDescriptor>> choices = new ArrayList<>();
        choices.add(new LinkedHashMap<>());
        for (OneofDescriptor oneof : type.getRealOneofs()) {
            List<Map<OneofDescriptor, FieldDescriptor>> next = new ArrayList<>();
            for (Map<OneofDescriptor, FieldDescriptor> choice : choices) {
                for (FieldDescriptor field : oneof.getFields()) {
                    Map<OneofDescriptor, FieldDescriptor> extended = new LinkedHashMap<>(choice);
                    extended.put(oneof, field);
                    next.add(extended);
                }
            }
            choices = next;
        }
        List<Message> messages = new ArrayList<>();
        for (Map<OneofDescriptor, FieldDescriptor> choice : choices) {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(type);
            for (FieldDescriptor field : type.getFields()) {
                OneofDescriptor oneof = field.getRealContainingOneof();
                if (oneof != null && choice.get(oneof) != field) {
                    continue;
                }
                if (field.isMapField()) {
                    Descriptor entry = field.getMessageType();
                    DynamicMessage.Builder pair = DynamicMessage.newBuilder(entry);
                    pair.setField(entry.findFieldByName("key"), sample(entry.findFieldByName("key"), depth));
                    pair.setField(entry.findFieldByName("value"), sample(entry.findFieldByName("value"), depth));
                    builder.addRepeatedField(field, pair.build());
                } else if (field.isRepeated()) {
                    builder.addRepeatedField(field, sample(field, depth));
                    builder.addRepeatedField(field, sample(field, depth));
                } else {
                    builder.setField(field, sample(field, depth));
                }
            }
            messages.add(builder.build());
        }
        return messages;
    }

    static Object sample(FieldDescriptor field, int depth) {
        return switch (field.getJavaType()) {
            case INT -> 7 + field.getNumber();
            case LONG -> 1_763_798_400_000L + field.getNumber();
            case FLOAT -> 1.5f;
            case DOUBLE -> 2.5;
            case BOOLEAN -> true;
            case STRING -> field.getName() + "-value";
            case BYTE_STRING -> ByteString.copyFromUtf8(field.getName());
            case ENUM -> field.getEnumType()
                    .getValues()
                    .get(field.getEnumType().getValues().size() - 1);
            case MESSAGE -> depth > 4
                    ? DynamicMessage.getDefaultInstance(field.getMessageType())
                    : filled(field.getMessageType(), depth + 1).get(0);
        };
    }

    @Test
    void everyFieldOfTheCheckpointMessagesSurvivesTheWire() throws Exception {
        for (Message prototype : MESSAGES) {
            for (Message message : filled(prototype.getDescriptorForType(), 0)) {
                Message parsed = prototype.getParserForType().parseFrom(message.toByteArray());
                assertThat(parsed.getAllFields())
                        .as(prototype.getDescriptorForType().getFullName())
                        .hasSize((int) prototype.getDescriptorForType().getFields().stream()
                                        .filter(f -> f.getRealContainingOneof() == null)
                                        .count()
                                + prototype
                                        .getDescriptorForType()
                                        .getRealOneofs()
                                        .size());
                assertThat(DynamicMessage.parseFrom(prototype.getDescriptorForType(), parsed.toByteArray()))
                        .isEqualTo(message);
            }
        }
    }

    @Test
    void theRunStatesOfRestartsAndPausesFollowTheFirstOnes() {
        assertThat(RunState.RUN_STATE_RESTARTING.getNumber()).isEqualTo(8);
        assertThat(RunState.RUN_STATE_PAUSING.getNumber()).isEqualTo(9);
        assertThat(RunState.RUN_STATE_PAUSED.getNumber()).isEqualTo(10);
        assertThat(RunState.RUN_STATE_RETRY_WAIT.getNumber()).isEqualTo(11);
    }

    @ParameterizedTest
    @EnumSource(CheckpointType.class)
    void checkpointTypes(CheckpointType type) {
        assertThat(ProtoMapping.fromProto(ProtoMapping.toProto(type))).isEqualTo(type);
    }

    @Test
    void unknownCheckpointTypesAreRejected() {
        assertThatThrownBy(() -> ProtoMapping.fromProto(
                        org.devlive.pipeforge.engine.rpc.proto.CheckpointType.CHECKPOINT_TYPE_UNSPECIFIED))
                .hasMessage("Unknown checkpoint type CHECKPOINT_TYPE_UNSPECIFIED");
    }

    @Test
    void stateHandlesInlineAndInFiles() throws Exception {
        StateHandle inline =
                new StateHandle("orders", "source", 2, new StateHandle.Inline(new byte[] {1, 2, 3}), 3, -7);
        StateHandle file = new StateHandle(
                "warehouse", "sink", 1, new StateHandle.File("warehouse-2.state"), 1 << 20, 0x1234_5678);
        for (StateHandle handle : List.of(inline, file)) {
            byte[] bytes = ProtoMapping.toProto(handle).toByteArray();
            assertThat(ProtoMapping.fromProto(org.devlive.pipeforge.engine.rpc.proto.StateHandle.parseFrom(bytes)))
                    .isEqualTo(handle);
        }
        assertThat(inline.location()).hasToString("Inline[3 bytes]");
        assertThatThrownBy(() -> ProtoMapping.fromProto(org.devlive.pipeforge.engine.rpc.proto.StateHandle.newBuilder()
                        .setNodeId("orders")
                        .build()))
                .hasMessage("The state of node 'orders' has neither inline bytes nor a file");
    }

    @Test
    void checkpointTaskMetrics() throws Exception {
        CheckpointTaskMetrics metrics = new CheckpointTaskMetrics(3, 120, 45, 9_000);
        byte[] bytes = ProtoMapping.toProto(metrics).toByteArray();
        assertThat(ProtoMapping.fromProto(
                        org.devlive.pipeforge.engine.rpc.proto.CheckpointTaskMetrics.parseFrom(bytes)))
                .isEqualTo(metrics);
    }

    @Test
    void theStatesATaskRestoresKeepTheirNodesAndOrder() throws Exception {
        Map<String, List<StateHandle>> states = new LinkedHashMap<>();
        states.put(
                "orders",
                List.of(
                        new StateHandle("orders", "source", 1, new StateHandle.Inline(new byte[] {1}), 1, 1),
                        new StateHandle("orders", "source", 1, new StateHandle.File("orders-3.state"), 40_000, 2)));
        states.put("mapper", List.of());
        byte[] bytes = ProtoMapping.toProtoRestore(states).toByteArray();
        Map<String, List<StateHandle>> back =
                ProtoMapping.fromProto(org.devlive.pipeforge.engine.rpc.proto.TaskRestore.parseFrom(bytes));
        assertThat(back).containsExactlyEntriesOf(states);

        var twice = org.devlive.pipeforge.engine.rpc.proto.TaskRestore.newBuilder()
                .addNodes(org.devlive.pipeforge.engine.rpc.proto.NodeRestore.newBuilder()
                        .setNodeId("a"))
                .addNodes(org.devlive.pipeforge.engine.rpc.proto.NodeRestore.newBuilder()
                        .setNodeId("a"))
                .build();
        assertThatThrownBy(() -> ProtoMapping.fromProto(twice)).hasMessage("The restore lists node 'a' twice");
    }
}
