/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.engine.rpc.grpc.BlobClient;
import org.devlive.pipeforge.engine.rpc.grpc.BlobServer;
import org.devlive.pipeforge.engine.rpc.proto.PluginRef;
import org.devlive.pipeforge.engine.worker.PluginCache;
import org.devlive.pipeforge.plugin.PluginManager;
import org.devlive.pipeforge.plugin.TestPlugins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A plugin installed on the master only, loaded by a worker through the BlobService. */
class PluginBlobsTest {

    @TempDir
    Path dir;

    PluginManager installed;
    PluginBlobs blobs;
    Server server;
    ManagedChannel channel;

    @BeforeEach
    void start() throws Exception {
        TestPlugins plugins = new TestPlugins(dir.resolve("build"));
        plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("7"), "");
        installed = PluginManager.load(
                List.of(dir.resolve("build/plugins")), dir.resolve("master-cache"), PluginManager.Options.defaults());
        blobs = new PluginBlobs(installed, dir.resolve("blobs"));
        String name = "blobs-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(name)
                .addService(new BlobServer(blobs))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).build();
    }

    @AfterEach
    void stop() throws InterruptedException {
        channel.shutdownNow();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        installed.close();
    }

    @Test
    void aWorkerLoadsAPluginOnlyTheMasterHas() throws Exception {
        PluginRef ref = blobs.ref("alpha@1.0.0");
        assertThat(ref.getId()).isEqualTo("alpha");
        assertThat(ref.getVersion()).isEqualTo("1.0.0");
        assertThat(ref.getSha256()).hasSize(64);
        assertThat(blobs.ref("alpha@1.0.0")).as("packed once").isEqualTo(ref);
        assertThat(blobs.find(ref.getSha256()))
                .hasValueSatisfying(file -> assertThat(file).isRegularFile());
        assertThat(blobs.find("00".repeat(32))).isEmpty();

        BlobClient client = new BlobClient(channel);
        try (PluginCache worker = new PluginCache(
                client::fetch,
                dir.resolve("worker"),
                (kind, type, plugin) -> {
                    throw new AssertionError("no builtins");
                },
                PluginManager.Options.defaults())) {
            Factory factory = worker.forRun("run-1", List.of(ref))
                    .resolve(NodeKind.SOURCE, "alpha-source", "alpha@1.0.0")
                    .factory();
            assertThat(factory.getClass().getMethod("libraryVersion").invoke(factory))
                    .isEqualTo("7");
            assertThat(worker.downloads()).isEqualTo(1);
            try (var files = Files.list(dir.resolve("worker/blobs"))) {
                assertThat(files).containsExactly(dir.resolve("worker/blobs").resolve(ref.getSha256() + ".zip"));
            }
        }
    }

    @Test
    void builtinsHaveNoPackageAndUnknownPluginsAreRefused() {
        assertThat(blobs.ref("builtin").getSha256()).isEmpty();
        assertThatThrownBy(() -> blobs.ref("beta@1.0.0")).isInstanceOfSatisfying(PipeForgeException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE);
            assertThat(e.params()).containsEntry("reason", "the master has no such plugin");
        });
    }
}
