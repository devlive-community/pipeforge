/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.ComponentProvider;
import org.devlive.pipeforge.engine.rpc.ChainCodec;
import org.devlive.pipeforge.engine.rpc.proto.PluginRef;
import org.devlive.pipeforge.plugin.PluginManager;
import org.devlive.pipeforge.plugin.PluginPackage;
import org.devlive.pipeforge.plugin.PluginRepository;
import org.devlive.pipeforge.plugin.TestPlugins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plugin packages downloaded, cached and loaded per run on a worker. */
class PluginCacheTest {

    @TempDir
    Path dir;

    PluginPackage.Packed alpha;
    final List<String> fetched = new java.util.concurrent.CopyOnWriteArrayList<>();
    final Map<String, Path> served = new java.util.concurrent.ConcurrentHashMap<>();

    /** Copies the served file, the way BlobClient writes a download. */
    final PluginCache.Fetcher fetcher = (sha256, name, target) -> {
        fetched.add(name);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(served.get(sha256), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    };

    record Builtin(String plugin) implements Component {
        @Override
        public Factory factory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassLoader classLoader() {
            return PluginCacheTest.class.getClassLoader();
        }

        @Override
        public <T> T call(String operation, Callable<T> action) {
            throw new UnsupportedOperationException();
        }
    }

    final ComponentProvider builtin = (kind, type, plugin) -> new Builtin("builtin:" + type);

    @BeforeEach
    void build() throws Exception {
        TestPlugins plugins = new TestPlugins(dir.resolve("build"));
        Path root = plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("7"), "");
        alpha = PluginPackage.pack(PluginRepository.read(root), dir.resolve("master"));
        served.put(alpha.sha256(), alpha.file());
    }

    PluginCache cache() {
        return new PluginCache(fetcher, dir.resolve("worker"), builtin, PluginManager.Options.defaults());
    }

    List<PluginRef> refs() {
        return List.of(ChainCodec.pluginRef("alpha@1.0.0").toBuilder()
                .setSha256(alpha.sha256())
                .build());
    }

    static Object libraryVersion(Component component) throws ReflectiveOperationException {
        Factory factory = component.factory();
        return factory.getClass().getMethod("libraryVersion").invoke(factory);
    }

    @Test
    void downloadsAPackageOnceAndLoadsItPerRun() throws Exception {
        try (PluginCache cache = cache()) {
            Component one = cache.forRun("run-1", refs()).resolve(NodeKind.SOURCE, "alpha-source", "alpha@1.0.0");
            Component two = cache.forRun("run-2", refs()).resolve(NodeKind.SOURCE, "alpha-source", "alpha@1.0.0");
            Component again = cache.forRun("run-1", refs()).resolve(NodeKind.SOURCE, "alpha-source", "alpha@1.0.0");

            assertThat(libraryVersion(one)).isEqualTo("7");
            assertThat(one.plugin()).isEqualTo("alpha@1.0.0");
            assertThat(fetched).containsExactly("alpha@1.0.0");
            assertThat(cache.downloads()).isEqualTo(1);
            assertThat(one.classLoader()).as("a loader per run").isNotSameAs(two.classLoader());
            assertThat(again.classLoader()).as("one loader within a run").isSameAs(one.classLoader());
            assertThat(cache.openLoaders()).isEqualTo(2);
        }
        try (PluginCache restarted = cache()) {
            Component component =
                    restarted.forRun("run-3", refs()).resolve(NodeKind.SOURCE, "alpha-source", "alpha@1.0.0");
            assertThat(libraryVersion(component)).isEqualTo("7");
            assertThat(restarted.downloads()).as("the cache directory is kept").isZero();
        }
    }

    /** Resolves a component of run-1 and returns only a weak reference to its loader. */
    WeakReference<ClassLoader> loadAndForget(PluginCache cache) throws Exception {
        Component component = cache.forRun("run-1", refs()).resolve(NodeKind.SOURCE, "alpha-source", "alpha@1.0.0");
        assertThat(libraryVersion(component)).isEqualTo("7");
        return new WeakReference<>(component.classLoader());
    }

    @Test
    void theLoadersOfARunAreCollectedWhenItIsReleased() throws Exception {
        try (PluginCache cache = cache()) {
            WeakReference<ClassLoader> loader = loadAndForget(cache);
            cache.release("run-1");
            cache.release("run-1");
            assertThat(cache.openLoaders()).isZero();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (loader.get() != null && System.nanoTime() < deadline) {
                System.gc();
                Thread.sleep(20);
            }
            assertThat(loader.get()).as("the class loader is collected").isNull();
        }
    }

    @Test
    void refusesPluginsWithoutAPackageAndPassesBuiltinsOn() {
        try (PluginCache cache = cache()) {
            ChainCodec.ComponentResolver resolver = cache.forRun(
                    "run-1",
                    List.of(PluginRef.newBuilder()
                            .setId("beta")
                            .setVersion("1.0")
                            .build()));
            assertThat(resolver.resolve(NodeKind.TRANSFORM, "field-mapper", "builtin")
                            .plugin())
                    .isEqualTo("builtin:field-mapper");
            for (String plugin : List.of("beta@1.0", "gamma@2.0")) {
                assertThatThrownBy(() -> resolver.resolve(NodeKind.SOURCE, "x", plugin))
                        .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                            assertThat(e.code()).isEqualTo(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE);
                            assertThat(e.params()).containsEntry("plugin", plugin);
                        });
            }
            assertThat(fetched).isEmpty();
        }
    }

    @Test
    void aPackageOfAnotherPluginIsRefused() {
        try (PluginCache cache = cache()) {
            List<PluginRef> wrong = List.of(ChainCodec.pluginRef("beta@1.0.0").toBuilder()
                    .setSha256(alpha.sha256())
                    .build());
            assertThatThrownBy(() -> cache.forRun("run-1", wrong).resolve(NodeKind.SOURCE, "beta-source", "beta@1.0.0"))
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE);
                        assertThat(e.getMessage()).contains("the package holds alpha@1.0.0");
                    });
            assertThat(cache.openLoaders()).isZero();
        }
    }

    @Test
    void concurrentRunsDownloadAPackageOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (PluginCache cache = cache()) {
            List<Future<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String run = "run-" + (i % 4);
                calls.add(pool.submit(() -> libraryVersion(
                        cache.forRun(run, refs()).resolve(NodeKind.SOURCE, "alpha-source", "alpha@1.0.0"))));
            }
            for (Future<Object> call : calls) {
                assertThat(call.get(30, TimeUnit.SECONDS)).isEqualTo("7");
            }
            assertThat(cache.downloads()).isEqualTo(1);
            assertThat(cache.openLoaders()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }
}
