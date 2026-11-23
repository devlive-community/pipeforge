/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.CheckpointFiles;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.StateHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** States are written off the task thread: small ones inline, larger ones as files. */
class StateUploaderTest {

    static final ExecutionAttemptId TASK = new ExecutionAttemptId("run-1", "orders", 2, 0);

    @TempDir
    Path dir;

    static StateUploader.Pending state(int size) {
        return new StateUploader.Pending("orders", "source", 2, 3, new byte[size]);
    }

    static PipeForgeException failure(CompletableFuture<?> future) {
        Throwable error = future.handle((v, e) -> e).join();
        assertThat(error).hasCauseInstanceOf(PipeForgeException.class);
        return (PipeForgeException) error.getCause();
    }

    @Test
    void smallStatesTravelInlineWithoutStorage() {
        try (StateUploader uploader = new StateUploader(null, 16, "w")) {
            CompletableFuture<List<StateHandle>> handles = uploader.upload(TASK, 1, List.of(state(16)));
            assertThat(handles).isCompleted();
            CRC32C crc = new CRC32C();
            crc.update(new byte[16]);
            assertThat(handles.join())
                    .containsExactly(new StateHandle(
                            "orders", "source", 3, new StateHandle.Inline(new byte[16]), 16, (int) crc.getValue()));
        }
    }

    @Test
    void largerStatesAreWrittenOnAnotherThreadWhileTheTaskGoesOn() throws Exception {
        BlockingStorage storage = new BlockingStorage(dir);
        try (StateUploader uploader = new StateUploader(storage, 16, "w")) {
            CompletableFuture<List<StateHandle>> handles = uploader.upload(TASK, 4, List.of(state(17), state(3)));
            assertThat(storage.writing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(handles).as("the caller is not held up by the write").isNotDone();
            storage.release.countDown();
            assertThat(handles).succeedsWithin(Duration.ofSeconds(5));
            assertThat(handles.join())
                    .extracting(StateHandle::location)
                    .containsExactly(new StateHandle.File("orders-2.state"), new StateHandle.Inline(new byte[3]));
            byte[] file = storage.read("run-1/chk-4/orders-2.state");
            assertThat(CheckpointFiles.readState(file, 4, "orders", "orders-2.state"))
                    .hasSize(17);
        }
    }

    @Test
    void aStateThatCannotBeWrittenFailsTheUpload() {
        try (StateUploader uploader = new StateUploader(null, 16, "w")) {
            PipeForgeException error = failure(uploader.upload(TASK, 5, List.of(state(17))));
            assertThat(error.code()).isEqualTo(ErrorCode.CHECKPOINT_FAILED);
            assertThat(error.getMessage()).contains("17 bytes", "no checkpoint storage");
        }
        BlockingStorage storage = new BlockingStorage(dir);
        storage.fail = true;
        storage.release.countDown();
        try (StateUploader uploader = new StateUploader(storage, 16, "w")) {
            PipeForgeException error = failure(uploader.upload(TASK, 6, List.of(state(17))));
            assertThat(error.getMessage()).contains("writing orders-2.state to", "the bucket is gone");
        }
    }
}
