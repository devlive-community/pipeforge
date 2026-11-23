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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.CheckpointMetadata;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.OperatorState;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.SubtaskState;
import org.devlive.pipeforge.engine.rpc.proto.CheckpointType;
import org.junit.jupiter.api.Test;

/** The checkpoint files of protocol section 5: round trips, damage, versions and golden files. */
class CheckpointFilesTest {

    static final Path GOLDEN = Path.of("..", "protocol-compat", "checkpoint", "v1");

    /** A checkpoint of a JDBC → JDBC job: an enumerator, a reader state inline, a sink state in a file. */
    static CheckpointMetadata sample() {
        return CheckpointMetadata.newBuilder()
                .setJobId("job-1")
                .setRunId("3d0f9c1a-5b7e-4c2a-9f10-6a8e2b4c7d01")
                .setCheckpointId(7)
                .setType(CheckpointType.CHECKPOINT_TYPE_CHECKPOINT)
                .setTriggerTimestamp(1_763_798_400_000L)
                .setCompleteTimestamp(1_763_798_401_250L)
                .setEngineVersion("0.1.0")
                .setDslVersion("1")
                .putVariables("biz_date", "2026-11-21")
                .putVariables("region", "cn")
                .setAttempt(2)
                .setJobName("orders-daily")
                .addOperators(OperatorState.newBuilder()
                        .setNodeId("orders")
                        .setOperatorKind("enumerator")
                        .setPlugin("mysql@0.1.0")
                        .setMaxParallelism(128)
                        .setStateBackend("heap")
                        .setParallelism(1)
                        .addSubtasks(SubtaskState.newBuilder()
                                .setSubtask(0)
                                .setSerializerVersion(2)
                                .setInline(ByteString.copyFromUtf8("[orders#3, orders#4]"))
                                .setSize(20)
                                .setCrc32C(123)))
                .addOperators(OperatorState.newBuilder()
                        .setNodeId("warehouse")
                        .setOperatorKind("sink")
                        .setPlugin("postgres@0.1.0")
                        .setMaxParallelism(128)
                        .setStateBackend("heap")
                        .setParallelism(4)
                        .addSubtasks(SubtaskState.newBuilder()
                                .setSubtask(2)
                                .setSerializerVersion(1)
                                .setFile(CheckpointFiles.stateFile("warehouse", 2))
                                .setSize(40_000)
                                .setCrc32C(456)))
                .build();
    }

    static byte[] state() {
        return "watermark id=4812000".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void metadataSurvivesAWriteAndARead() {
        byte[] file = CheckpointFiles.writeMetadata(sample());
        assertThat(ByteBuffer.wrap(file).getInt()).isEqualTo(CheckpointFiles.METADATA_MAGIC);
        assertThat(CheckpointFiles.readMetadata(file, "7")).isEqualTo(sample());
        assertThat(CheckpointFiles.writeMetadata(sample())).as("deterministic").isEqualTo(file);
    }

    @Test
    void aStateFileNamesItsCheckpointAndNode() {
        byte[] file = CheckpointFiles.writeState(7, "warehouse", state());
        assertThat(CheckpointFiles.readState(file, 7, "warehouse", "warehouse-2.state"))
                .isEqualTo(state());
        assertThatThrownBy(() -> CheckpointFiles.readState(file, 8, "warehouse", "warehouse-2.state"))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.STATE_CORRUPTED);
                    assertThat(e.getMessage())
                            .isEqualTo(
                                    "PF-CKPT-0002 The warehouse-2.state of checkpoint 8 is damaged: it belongs to node 'warehouse'"
                                            + " of checkpoint 7, not to node 'warehouse' of checkpoint 8");
                });
        assertThatThrownBy(() -> CheckpointFiles.readState(file, 7, "orders", "orders-2.state"))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.getMessage())
                        .contains("not to node 'orders'"));
        byte[] empty = CheckpointFiles.writeState(1, "n", new byte[0]);
        assertThat(CheckpointFiles.readState(empty, 1, "n", "n-0.state")).isEmpty();
    }

    @Test
    void damageIsFoundWhereverItIs() {
        byte[] file = CheckpointFiles.writeMetadata(sample());
        for (int at : new int[] {0, 5, 9, 20, file.length - 1}) {
            byte[] damaged = file.clone();
            damaged[at] ^= 0x01;
            assertThatThrownBy(() -> CheckpointFiles.readMetadata(damaged, "7"))
                    .as("a flipped bit at %d", at)
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.code())
                            .isIn(ErrorCode.STATE_CORRUPTED, ErrorCode.STATE_INCOMPATIBLE));
        }
        byte[] cut = java.util.Arrays.copyOf(file, file.length - 3);
        assertThatThrownBy(() -> CheckpointFiles.readMetadata(cut, "7"))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.getMessage())
                        .contains("its length says"));
        assertThatThrownBy(() -> CheckpointFiles.readMetadata(new byte[3], "7"))
                .hasMessage(
                        "PF-CKPT-0002 The _metadata of checkpoint 7 is damaged: it has 3 bytes, fewer than the 16 of an empty file");
        byte[] state = CheckpointFiles.writeState(7, "n", state());
        assertThatThrownBy(() -> CheckpointFiles.readMetadata(state, "7"))
                .as("a state file is no metadata")
                .hasMessage(
                        "PF-CKPT-0002 The _metadata of checkpoint 7 is damaged: it starts with 0x50465354, not 0x50464350");
    }

    @Test
    void aNewerFormatIsIncompatibleNotDamaged() {
        byte[] file = CheckpointFiles.writeMetadata(sample());
        ByteBuffer.wrap(file).putInt(4, CheckpointFiles.FORMAT_VERSION + 1);
        assertThatThrownBy(() -> CheckpointFiles.readMetadata(file, "7"))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.STATE_INCOMPATIBLE);
                    assertThat(e.getMessage())
                            .isEqualTo(
                                    "PF-CKPT-0003 The _metadata of checkpoint 7 cannot be read: it has format version 2;"
                                            + " this engine reads 1 to 1");
                });
    }

    @Test
    void namesAndTheInlineThreshold() {
        assertThat(CheckpointFiles.stateFile("warehouse", 3)).isEqualTo("warehouse-3.state");
        assertThat(CheckpointFiles.enumeratorFile("orders")).isEqualTo("enum-orders.state");
        assertThat(CheckpointFiles.inline(16 * 1024, CheckpointFiles.DEFAULT_INLINE_THRESHOLD))
                .isTrue();
        assertThat(CheckpointFiles.inline(16 * 1024 + 1, CheckpointFiles.DEFAULT_INLINE_THRESHOLD))
                .isFalse();
    }

    /**
     * The files of format 1 stay readable: later engines read these bytes. -Dpipeforge.protocol.update
     * writes them anew, only when the format version changes.
     */
    @Test
    void theGoldenFilesOfFormat1AreRead() throws Exception {
        Path metadata = GOLDEN.resolve("_metadata");
        Path state = GOLDEN.resolve("warehouse-2.state");
        if (Boolean.getBoolean("pipeforge.protocol.update") || !Files.exists(metadata)) {
            Files.createDirectories(GOLDEN);
            Files.write(metadata, CheckpointFiles.writeMetadata(sample()));
            Files.write(state, CheckpointFiles.writeState(7, "warehouse", state()));
        }
        assertThat(CheckpointFiles.readMetadata(Files.readAllBytes(metadata), "7"))
                .isEqualTo(sample());
        assertThat(CheckpointFiles.readState(Files.readAllBytes(state), 7, "warehouse", "warehouse-2.state"))
                .isEqualTo(state());
        assertThat(Files.readAllBytes(metadata))
                .as("format 1 is written as it was")
                .isEqualTo(CheckpointFiles.writeMetadata(sample()));
    }
}
