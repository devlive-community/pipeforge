/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.common.plugin.PluginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginManagerTest {

    @TempDir
    Path dir;

    @SuppressWarnings("UnusedVariable") // documents which plugins the directory holds
    private PluginManager manager(TestPlugins plugins) {
        return PluginManager.load(
                List.of(dir.resolve("plugins"), dir.resolve("absent")),
                dir.resolve("cache"),
                PluginManager.Options.defaults());
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    @Test
    void twoPluginsUseDifferentVersionsOfTheSameLibrary() throws Exception {
        TestPlugins plugins = new TestPlugins(dir);
        plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("1"), "");
        plugins.plugin("beta", "1.0.0", "beta-source", plugins.library("2"), "");
        try (PluginManager manager = manager(plugins)) {
            assertThat(manager.plugins())
                    .extracting(InstalledPlugin::coordinate)
                    .containsExactly("alpha@1.0.0", "beta@1.0.0");
            try (PluginLease alpha =
                            manager.acquire(manager.resolve(PluginType.SOURCE, "alpha-source", null), "job-1");
                    PluginLease beta =
                            manager.acquire(manager.resolve(PluginType.SOURCE, "beta-source", null), "job-1")) {
                SourceFactory a = alpha.factory(PluginType.SOURCE, "alpha-source", SourceFactory.class);
                SourceFactory b = beta.factory(PluginType.SOURCE, "beta-source", SourceFactory.class);
                assertThat(call(a, "libraryVersion")).isEqualTo("1");
                assertThat(call(b, "libraryVersion")).isEqualTo("2");
                // The API is shared parent-first: the factories implement the engine's interface.
                assertThat(a).isInstanceOf(SourceFactory.class);
                assertThat(a.getClass().getClassLoader())
                        .isNotSameAs(b.getClass().getClassLoader());
                assertThat(alpha.factory(PluginType.SOURCE, "alpha-source", SourceFactory.class))
                        .isSameAs(a);
                assertThat(manager.openLoaders()).isEqualTo(2);
            }
            assertThat(manager.openLoaders()).isZero();
        }
    }

    @Test
    void invokerSetsTheContextClassLoader() throws Exception {
        TestPlugins plugins = new TestPlugins(dir);
        plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("1"), "");
        try (PluginManager manager = manager(plugins);
                PluginLease lease = manager.acquire(manager.plugins().get(0), "job")) {
            SourceFactory factory = lease.factory(PluginType.SOURCE, "alpha-source", SourceFactory.class);
            Object inside = lease.invoker().call("context", () -> call(factory, "contextLoader"));
            assertThat(inside).isEqualTo("PluginClassLoader[alpha@1.0.0]");
            assertThat(Thread.currentThread().getContextClassLoader()).isNotSameAs(lease.classLoader());
            assertThat(lease.owner()).isEqualTo("job");
            assertThat(lease.plugin().id()).isEqualTo("alpha");
        }
    }

    @Test
    void picksTheHighestVersionUnlessPinned() {
        TestPlugins plugins = new TestPlugins(dir);
        plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("1"), "");
        plugins.plugin("alpha", "1.10.0", "alpha-source", plugins.library("2"), "");
        plugins.plugin("alpha", "1.2.0", "alpha-source", plugins.library("3"), "");
        try (PluginManager manager = manager(plugins)) {
            assertThat(manager.resolve(PluginType.SOURCE, "alpha-source", null).version())
                    .isEqualTo("1.10.0");
            assertThat(manager.resolve(PluginType.SOURCE, "alpha-source", "alpha")
                            .version())
                    .isEqualTo("1.10.0");
            assertThat(manager.resolve(PluginType.SOURCE, "alpha-source", "alpha@1.2.0")
                            .version())
                    .isEqualTo("1.2.0");
            assertThatThrownBy(() -> manager.resolve(PluginType.SOURCE, "alpha-source", "alpha@9.9.9"))
                    .isInstanceOf(PipeForgeException.class)
                    .hasMessage("PF-PLUG-0001 The source plugin 'alpha@9.9.9' is not installed");
            assertThatThrownBy(() -> manager.resolve(PluginType.SINK, "alpha-source", null))
                    .hasMessage("PF-PLUG-0001 The sink plugin 'alpha-source' is not installed");
        }
    }

    @Test
    void reportsAmbiguousComponents() {
        TestPlugins plugins = new TestPlugins(dir);
        plugins.plugin("alpha", "1.0.0", "shared", plugins.library("1"), "");
        plugins.plugin("beta", "1.0.0", "shared", plugins.library("2"), "");
        try (PluginManager manager = manager(plugins)) {
            assertThatThrownBy(() -> manager.resolve(PluginType.SOURCE, "shared", null))
                    .satisfies(
                            e -> assertThat(((PipeForgeException) e).code()).isEqualTo(ErrorCode.COMPONENT_AMBIGUOUS))
                    .hasMessageContaining("alpha, beta");
            assertThat(manager.resolve(PluginType.SOURCE, "shared", "beta").id())
                    .isEqualTo("beta");
        }
    }

    @Test
    void unloadedClassLoadersAreGarbageCollected() throws Exception {
        TestPlugins plugins = new TestPlugins(dir);
        plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("1"), "");
        try (PluginManager manager = manager(plugins)) {
            WeakReference<ClassLoader> loader = useAndRelease(manager);
            assertThat(manager.openLoaders()).isZero();
            for (int i = 0; i < 50 && loader.get() != null; i++) {
                System.gc();
                Thread.sleep(20);
            }
            assertThat(loader.get()).as("plugin class loader was collected").isNull();
        }
    }

    private static WeakReference<ClassLoader> useAndRelease(PluginManager manager) throws Exception {
        PluginLease first = manager.acquire(manager.plugins().get(0), "job-1");
        PluginLease second = manager.acquire(manager.plugins().get(0), "job-2");
        assertThat(second.classLoader()).isSameAs(first.classLoader());
        SourceFactory factory = first.factory(PluginType.SOURCE, "alpha-source", SourceFactory.class);
        assertThat(call(factory, "libraryVersion")).isEqualTo("1");
        WeakReference<ClassLoader> loader = new WeakReference<>(first.classLoader());
        first.close();
        first.close();
        assertThat(manager.openLoaders()).isEqualTo(1);
        second.close();
        assertThatThrownBy(() -> first.factory(PluginType.SOURCE, "alpha-source", SourceFactory.class))
                .hasMessageContaining("is closed");
        return loader;
    }

    @Test
    void refusesPluginsThatChangeTheWholeJvm() {
        TestPlugins plugins = new TestPlugins(dir);
        plugins.plugin(
                "rogue", "1.0.0", "rogue-source", plugins.library("1"), "public void stop() { System.exit(3); }");
        try (PluginManager manager = manager(plugins)) {
            InstalledPlugin rogue = manager.plugins().get(0);
            assertThatThrownBy(() -> manager.acquire(rogue, "job"))
                    .isInstanceOf(PipeForgeException.class)
                    .satisfies(
                            e -> assertThat(((PipeForgeException) e).code()).isEqualTo(ErrorCode.PLUGIN_FORBIDDEN_CALL))
                    .hasMessageContaining("java.lang.System.exit")
                    .hasMessageContaining("org.example.rogue.v1_0_0.Factory");
            assertThat(manager.openLoaders()).isZero();
        }
        try (PluginManager trusting = PluginManager.load(
                List.of(dir.resolve("plugins")),
                dir.resolve("cache"),
                new PluginManager.Options(PluginManager.class.getClassLoader(), List.of("org.example.rogue.")))) {
            trusting.acquire(trusting.plugins().get(0), "job").close();
        }
    }

    @Test
    void loadsZippedPluginsAndRejectsBrokenEntries() throws IOException {
        TestPlugins plugins = new TestPlugins(dir);
        Path extracted = plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("1"), "");
        Path zip = dir.resolve("plugins/alpha-1.0.0.zip");
        zipDirectory(extracted, zip);
        deleteRecursively(extracted);
        Files.createDirectories(dir.resolve("plugins/empty"));
        Files.writeString(
                dir.resolve("plugins/empty/plugin.yaml"),
                "id: empty\nname: E\nversion: 1.0.0\napi-version: 0.x\nprovides: [{type: source, identifier: e, class: a.B}]\n");
        Files.createDirectories(dir.resolve("plugins/broken"));
        Files.writeString(dir.resolve("plugins/broken/plugin.yaml"), "id: Broken\n");
        Files.writeString(dir.resolve("plugins/notes.txt"), "ignored");
        try (PluginManager manager = manager(plugins)) {
            assertThat(manager.plugins())
                    .extracting(InstalledPlugin::coordinate)
                    .containsExactly("alpha@1.0.0");
            assertThat(manager.plugins().get(0).root().getParent()).isEqualTo(dir.resolve("cache"));
            assertThat(manager.unavailable()).hasSize(2);
            assertThat(manager.unavailable().values())
                    .anyMatch(r -> r.contains("has no jars in lib/"))
                    .anyMatch(r -> r.startsWith("Invalid plugin.yaml"));
        }
        // A second scan reuses the extracted copy.
        try (PluginManager again = manager(plugins)) {
            assertThat(again.plugins()).hasSize(1);
        }
    }

    @Test
    void marksPluginsForAnotherApiAsUnavailable() {
        TestPlugins plugins = new TestPlugins(dir);
        Path plugin = plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("1"), "");
        try {
            Path yaml = plugin.resolve("plugin.yaml");
            Files.writeString(
                    yaml, Files.readString(yaml).replaceAll("api-version: \"\\d+\\.x\"", "api-version: \"99.x\""));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        try (PluginManager manager = manager(plugins)) {
            assertThat(manager.plugins()).isEmpty();
            assertThat(manager.unavailable()).containsKey("alpha@1.0.0");
            assertThat(manager.unavailable().get("alpha@1.0.0")).startsWith("built for API 99.x");
        }
    }

    @Test
    void comparesSemanticVersions() {
        assertThat(PluginManager.compareVersions("1.10.0", "1.9.0")).isPositive();
        assertThat(PluginManager.compareVersions("1.0.0-rc1", "1.0.0")).isNegative();
        assertThat(PluginManager.compareVersions("1.0.0", "1.0.0-rc1")).isPositive();
        assertThat(PluginManager.compareVersions("1.0.0-rc1", "1.0.0-rc2")).isNegative();
        assertThat(PluginManager.compareVersions("1.0", "1.0.0")).isZero();
        assertThat(PluginManager.compareVersions("1.x.0", "1.0.0")).isZero();
    }

    static void zipDirectory(Path source, Path zip) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream stream = new ZipOutputStream(out);
                Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                stream.putNextEntry(
                        new ZipEntry(source.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, stream);
                stream.closeEntry();
            }
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> files = Files.walk(path)) {
            for (Path p :
                    files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }
}
