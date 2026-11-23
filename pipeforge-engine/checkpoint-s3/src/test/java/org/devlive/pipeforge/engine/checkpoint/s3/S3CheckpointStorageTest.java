/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.checkpoint.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointPaths;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Checkpoint storage on S3, against LocalStack, opened through the s3 scheme like the processes do. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3CheckpointStorageTest {

    static final String IMAGE = "localstack/localstack:3.8";

    GenericContainer<?> localstack;
    Map<String, String> settings;

    @BeforeAll
    void start() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is needed for LocalStack");
        localstack = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("SERVICES", "s3")
                .withExposedPorts(4566)
                .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200));
        localstack.start();
        settings = Map.of(
                "s3.endpoint", "http://" + localstack.getHost() + ":" + localstack.getMappedPort(4566),
                "s3.region", "us-east-1",
                "s3.path-style", "true",
                "s3.access-key", "test",
                "s3.secret-key", "test");
        var execution = localstack.execInContainer("awslocal", "s3", "mb", "s3://checkpoints");
        assertThat(execution.getExitCode()).as(execution.getStderr()).isZero();
    }

    @AfterAll
    void stop() {
        if (localstack != null) {
            localstack.stop();
        }
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keepsCheckpointsUnderAPrefix() throws Exception {
        try (CheckpointStorage storage = CheckpointStorages.open("s3://checkpoints/cluster-a", settings)) {
            assertThat(storage).isInstanceOf(S3CheckpointStorage.class);
            assertThat(storage.location()).hasToString("s3://checkpoints/cluster-a/");
            String chk = CheckpointPaths.checkpoint("job-1", 7);
            storage.write(chk + "orders-0.state", bytes("a"));
            storage.write(chk + "orders-1.state", bytes("ccc"));
            assertThat(storage.read(chk + "orders-1.state")).isEqualTo(bytes("ccc"));
            assertThat(storage.exists(chk + "orders-0.state")).isTrue();
            assertThat(storage.exists(chk + "nothing")).isFalse();
            assertThatThrownBy(() -> storage.read(chk + "nothing")).isInstanceOf(NoSuchFileException.class);
            assertThat(storage.list("job-1/"))
                    .extracting(CheckpointStorage.Entry::path)
                    .containsExactly("job-1/chk-7/orders-0.state", "job-1/chk-7/orders-1.state");

            // _metadata alone is not a complete checkpoint on object storage: the marker is
            storage.write(chk + CheckpointStorages.METADATA, bytes("half"));
            assertThat(storage.isComplete(chk)).isFalse();
            storage.complete(chk, bytes("metadata"));
            assertThat(storage.isComplete(chk)).isTrue();

            storage.delete(chk);
            assertThat(storage.list("")).isEmpty();
            CheckpointStorages.probe(storage, "worker-1");
            assertThat(storage.list("")).isEmpty();
        }
    }

    @Test
    void deletesMoreKeysThanOneRequestTakes() throws Exception {
        try (CheckpointStorage storage = CheckpointStorages.open("s3://checkpoints/many", settings)) {
            String chk = CheckpointPaths.checkpoint("job-2", 1);
            for (int i = 0; i < S3CheckpointStorage.DELETE_BATCH + 5; i++) {
                storage.write(chk + "n-" + i + ".state", bytes("x"));
            }
            assertThat(storage.list(chk)).hasSize(S3CheckpointStorage.DELETE_BATCH + 5);
            storage.delete(chk);
            assertThat(storage.list(chk)).isEmpty();
        }
    }

    @Test
    void aMissingBucketOrUnreachableEndpointFailsTheProbe() throws Exception {
        try (CheckpointStorage missing = CheckpointStorages.open("s3://no-such-bucket/x", settings)) {
            assertThatThrownBy(() -> CheckpointStorages.probe(missing, "master"))
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
                        assertThat(e.getMessage())
                                .startsWith("PF-CKPT-0004 The checkpoint storage s3://no-such-bucket/x/");
                    });
        }
        assertThatThrownBy(() -> CheckpointStorages.open("s3:///prefix-only", settings))
                .hasMessageContaining("an s3 location needs a bucket");
    }
}
