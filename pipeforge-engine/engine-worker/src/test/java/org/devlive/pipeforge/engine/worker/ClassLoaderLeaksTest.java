/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.engine.rpc.ChainCodec;
import org.devlive.pipeforge.engine.rpc.proto.PluginRef;
import org.devlive.pipeforge.plugin.PluginManager;
import org.devlive.pipeforge.plugin.PluginPackage;
import org.devlive.pipeforge.plugin.PluginRepository;
import org.devlive.pipeforge.plugin.TestPlugins;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plugin class loaders of released runs followed until they are collected. */
class ClassLoaderLeaksTest {

    /** Starts a thread when the factory class initializes; it sleeps until it is interrupted. */
    private static final String LEAKY_THREAD =
            """
            static {
                Thread thread = new Thread(() -> {
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException e) {
                        // ends
                    }
                }, "leaky-plugin-thread");
                thread.setDaemon(true);
                thread.start();
            }
            """;

    @TempDir
    Path dir;

    final Map<String, Path> served = new ConcurrentHashMap<>();
    final AtomicLong clock = new AtomicLong(1);
    final AtomicInteger collections = new AtomicInteger();
    /** Holds a class of a plugin the way a static cache outside the plugin would. */
    static volatile @Nullable Class<?> cached;

    final PluginCache.Fetcher fetcher = (sha256, name, target) -> {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(served.get(sha256), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    };

    ClassLoaderLeaks leaks() {
        return new ClassLoaderLeaks(Duration.ofSeconds(60), clock::get, () -> {
            collections.incrementAndGet();
            System.gc();
        });
    }

    PluginCache cache(ClassLoaderLeaks leaks) {
        return new PluginCache(
                fetcher,
                dir.resolve("worker"),
                (kind, type, plugin) -> {
                    throw new UnsupportedOperationException();
                },
                PluginManager.Options.defaults(),
                leaks);
    }

    List<PluginRef> plugin(String id, String extraBody) throws java.io.IOException {
        TestPlugins plugins = new TestPlugins(dir.resolve("build-" + id));
        Path root = plugins.plugin(id, "1.0.0", id + "-source", plugins.library("7"), extraBody);
        PluginPackage.Packed packed = PluginPackage.pack(PluginRepository.read(root), dir.resolve("master"));
        served.put(packed.sha256(), packed.file());
        return List.of(ChainCodec.pluginRef(id + "@1.0.0").toBuilder()
                .setSha256(packed.sha256())
                .build());
    }

    /** Loads the factory of the plugin for run-1, then releases the run. */
    void runAndRelease(PluginCache cache, String id, List<PluginRef> refs, boolean keepClass) throws Exception {
        Component component = cache.forRun("run-1", refs).resolve(NodeKind.SOURCE, id + "-source", id + "@1.0.0");
        assertThat(PluginCacheTest.libraryVersion(component)).isEqualTo("7");
        if (keepClass) {
            cached = component.factory().getClass();
        }
        cache.release("run-1");
    }

    /** Checks after the timeout until the loader is collected or reported; returns what was reported. */
    List<ClassLoaderLeaks.Leak> checkUntilSettled(ClassLoaderLeaks leaks) throws InterruptedException {
        clock.addAndGet(TimeUnit.SECONDS.toNanos(61));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        List<ClassLoaderLeaks.Leak> found = new java.util.ArrayList<>();
        while (leaks.watching() > 0) {
            assertThat(System.nanoTime())
                    .as("the loader is collected or reported")
                    .isLessThan(deadline);
            found.addAll(leaks.check());
            Thread.sleep(20);
        }
        return found;
    }

    @Test
    void aPluginThatLeavesNothingBehindIsNotReported() throws Exception {
        ClassLoaderLeaks leaks = leaks();
        try (PluginCache cache = cache(leaks)) {
            runAndRelease(cache, "alpha", plugin("alpha", ""), false);
            assertThat(leaks.watching()).isEqualTo(1);

            // before the timeout nothing is reported and no collection is asked for
            for (int i = 0; i < 3; i++) {
                assertThat(leaks.check()).isEmpty();
            }
            assertThat(collections).hasValue(0);

            // the collection the guard asks for takes it, or one later does
            assertThat(checkUntilSettled(leaks)).isEmpty();
            assertThat(leaks.leaks()).isEmpty();
        }
    }

    @Test
    void aThreadThePluginLeftRunningIsNamedAsTheHolder() throws Exception {
        ClassLoaderLeaks leaks = leaks();
        try (PluginCache cache = cache(leaks)) {
            runAndRelease(cache, "leaky", plugin("leaky", LEAKY_THREAD), false);
            List<ClassLoaderLeaks.Leak> found = checkUntilSettled(leaks);

            assertThat(found).hasSize(1);
            ClassLoaderLeaks.Leak leak = found.get(0);
            assertThat(leak.plugin()).isEqualTo("leaky@1.0.0");
            assertThat(leak.run()).isEqualTo("run-1");
            assertThat(leak.holders()).containsExactly("thread leaky-plugin-thread (its context class loader)");
            assertThat(collections).as("a collection was asked for first").hasPositiveValue();
            assertThat(leaks.leaks()).containsExactly(leak);
            // reported once
            assertThat(leaks.check()).isEmpty();
        } finally {
            Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().equals("leaky-plugin-thread"))
                    .forEach(Thread::interrupt);
        }
    }

    @Test
    void aLoaderNoThreadHoldsIsReportedAsHeldByAStaticReference() throws Exception {
        ClassLoaderLeaks leaks = leaks();
        try (PluginCache cache = cache(leaks)) {
            runAndRelease(cache, "cached", plugin("cached", ""), true);
            List<ClassLoaderLeaks.Leak> found = checkUntilSettled(leaks);

            assertThat(found).singleElement().satisfies(leak -> {
                assertThat(leak.plugin()).isEqualTo("cached@1.0.0");
                assertThat(leak.holders()).isEmpty();
            });
        } finally {
            cached = null;
        }
    }

    @Test
    void theTimeoutMustNotBeNegative() {
        assertThatThrownBy(() -> new ClassLoaderLeaks(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ClassLoaderLeaks().watching()).isZero();
    }
}
