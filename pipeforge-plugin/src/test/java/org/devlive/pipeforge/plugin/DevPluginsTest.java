/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.common.plugin.PluginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevPluginsTest {

    @TempDir
    Path dir;

    private Path tree() throws Exception {
        Path tree = Files.createDirectories(dir.resolve("checkout"));
        Files.writeString(tree.resolve("pom.xml"), "<project/>");
        return tree;
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    @Test
    void findsTheOutermostDirectoryWithABuildFile() throws Exception {
        Path tree = tree();
        Path server = Files.createDirectories(tree.resolve("pipeforge-server/src/main"));
        Files.writeString(tree.resolve("pipeforge-server/pom.xml"), "<project/>");
        Path docs = Files.createDirectories(tree.resolve("docs/content"));

        assertThat(DevPlugins.findRoot(tree)).contains(tree.toAbsolutePath().normalize());
        assertThat(DevPlugins.findRoot(server)).contains(tree.toAbsolutePath().normalize());
        assertThat(DevPlugins.findRoot(tree.resolve("pipeforge-server")))
                .contains(tree.toAbsolutePath().normalize());
        assertThat(DevPlugins.findRoot(docs)).contains(tree.toAbsolutePath().normalize());
        assertThat(DevPlugins.findRoot(Files.createDirectories(dir.resolve("elsewhere"))))
                .isEmpty();
    }

    @Test
    void loadsAPluginModuleFromItsBuildOutput() throws Exception {
        Path tree = tree();
        TestPlugins plugins = new TestPlugins(dir);
        Path module = plugins.module(
                tree, "pipeforge-connectors", "alpha", "1.0.0", "alpha-source", plugins.library("7"), "");

        PluginRepository.Scan scan = DevPlugins.scan(tree);

        assertThat(scan.rejected()).isEmpty();
        assertThat(scan.directories())
                .containsExactly(
                        new PluginRepository.Directory(tree.toAbsolutePath().normalize(), true, 1));
        InstalledPlugin plugin = scan.plugins().get(0);
        assertThat(plugin.coordinate()).isEqualTo("alpha@1.0.0");
        assertThat(plugin.root()).isEqualTo(module.toAbsolutePath().normalize());
        assertThat(plugin.classpath()).first().isEqualTo(plugin.root().resolve(DevPlugins.CLASSES));
        assertThat(plugin.classpath()).hasSize(2);
        try (PluginManager manager = PluginManager.load(scan, PluginManager.Options.defaults());
                PluginLease lease = manager.acquire(manager.resolve(PluginType.SOURCE, "alpha-source", null), "job")) {
            SourceFactory factory = lease.factory(PluginType.SOURCE, "alpha-source", SourceFactory.class);
            assertThat(call(factory, "libraryVersion")).isEqualTo("7");
            assertThat(factory.getClass().getClassLoader()).isInstanceOf(PluginClassLoader.class);
        }
    }

    @Test
    void onlyLooksTwoLevelsDeepAndSkipsBuildAndHiddenDirectories() throws Exception {
        Path tree = tree();
        TestPlugins plugins = new TestPlugins(dir);
        Path library = plugins.library("1");
        plugins.module(tree, "pipeforge-connectors", "alpha", "1.0.0", "alpha-source", library, "");
        plugins.module(tree, ".git", "hidden", "1.0.0", "hidden-source", library, "");
        plugins.module(tree, "target", "built", "1.0.0", "built-source", library, "");
        plugins.module(tree, "node_modules", "npm", "1.0.0", "npm-source", library, "");
        plugins.module(tree.resolve("tools/it"), "fixtures", "deep", "1.0.0", "deep-source", library, "");

        assertThat(DevPlugins.scan(tree).plugins())
                .extracting(InstalledPlugin::coordinate)
                .containsExactly("alpha@1.0.0");
    }

    @Test
    void rejectsModulesThatAreNotBuilt() throws Exception {
        Path tree = tree();
        TestPlugins plugins = new TestPlugins(dir);
        Path library = plugins.library("1");
        Path uncompiled = plugins.module(tree, "group", "a-uncompiled", "1.0.0", "a", library, "");
        deleteRecursively(uncompiled.resolve(DevPlugins.CLASSES));
        Path unlisted = plugins.module(tree, "group", "b-unlisted", "1.0.0", "b", library, "");
        Files.delete(unlisted.resolve(DevPlugins.CLASSPATH_FILE));
        Path stale = plugins.module(tree, "group", "c-stale", "1.0.0", "c", library, "");
        Files.writeString(stale.resolve(DevPlugins.CLASSPATH_FILE), dir.resolve("gone.jar") + "\n");
        Path invalid = plugins.module(tree, "group", "d-invalid", "1.0.0", "d", library, "");
        Files.writeString(invalid.resolve(DevPlugins.DESCRIPTOR), "id: d-invalid\n");

        PluginRepository.Scan scan = DevPlugins.scan(tree);

        assertThat(scan.plugins()).isEmpty();
        assertThat(scan.rejected())
                .extracting(PluginRepository.Rejected::reason)
                .satisfiesExactly(
                        r -> assertThat(r)
                                .startsWith("a-uncompiled@1.0.0 is not compiled")
                                .endsWith("build it with ./mvnw compile -pl group/a-uncompiled -am"),
                        r -> assertThat(r)
                                .startsWith("b-unlisted@1.0.0 has no dependency list")
                                .endsWith("build it with ./mvnw compile -pl group/b-unlisted -am"),
                        r -> assertThat(r)
                                .startsWith("c-stale@1.0.0 misses its dependency")
                                .contains("gone.jar"),
                        r -> assertThat(r).startsWith("Invalid plugin.yaml"));
        assertThat(scan.directories())
                .extracting(PluginRepository.Directory::plugins)
                .containsExactly(0);
    }

    @Test
    void reportsAMissingRoot() {
        Path missing = dir.resolve("missing");
        PluginRepository.Scan scan = DevPlugins.scan(missing);
        assertThat(scan.plugins()).isEmpty();
        assertThat(scan.directories()).containsExactly(new PluginRepository.Directory(missing, false, 0));
    }

    @Test
    void developmentPluginsReplaceInstalledOnesWithTheSameCoordinate() throws Exception {
        Path tree = tree();
        TestPlugins plugins = new TestPlugins(dir);
        plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("1"), "");
        plugins.plugin("beta", "1.0.0", "beta-source", plugins.library("2"), "");
        plugins.module(tree, "pipeforge-connectors", "alpha", "1.0.0", "alpha-source", plugins.library("3"), "");
        Files.createDirectories(tree.resolve("pipeforge-connectors/broken/src/main/plugin"));
        Files.writeString(tree.resolve("pipeforge-connectors/broken/" + DevPlugins.DESCRIPTOR), "id: broken\n");
        Files.writeString(dir.resolve("plugins/broken.zip"), "not a zip");

        PluginRepository.Scan installed =
                new PluginRepository(dir.resolve("cache")).scan(List.of(dir.resolve("plugins")));
        PluginRepository.Scan scan = DevPlugins.overlay(installed, DevPlugins.scan(tree));

        assertThat(scan.plugins())
                .extracting(p -> p.coordinate() + " " + p.root().getFileName())
                .containsExactly("alpha@1.0.0 alpha", "beta@1.0.0 beta-1.0.0");
        assertThat(scan.rejected()).hasSize(2);
        assertThat(scan.directories())
                .extracting(PluginRepository.Directory::path)
                .containsExactly(
                        dir.resolve("plugins").toAbsolutePath().normalize(),
                        tree.toAbsolutePath().normalize());
        try (PluginManager manager = PluginManager.load(scan, PluginManager.Options.defaults());
                PluginLease lease = manager.acquire(manager.resolve(PluginType.SOURCE, "alpha-source", null), "job")) {
            SourceFactory factory = lease.factory(PluginType.SOURCE, "alpha-source", SourceFactory.class);
            assertThat(call(factory, "libraryVersion")).isEqualTo("3");
            assertThat(manager.unavailable()).hasSize(2);
        }
    }

    @Test
    void refusesForbiddenCallsInTheClassesDirectory() throws Exception {
        Path tree = tree();
        TestPlugins plugins = new TestPlugins(dir);
        plugins.module(
                tree,
                "pipeforge-connectors",
                "alpha",
                "1.0.0",
                "alpha-source",
                plugins.library("1"),
                "public void stop() { System.exit(1); }");
        try (PluginManager manager = PluginManager.load(DevPlugins.scan(tree), PluginManager.Options.defaults())) {
            InstalledPlugin plugin = manager.plugins().get(0);
            assertThatThrownBy(() -> manager.acquire(plugin, "job"))
                    .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.code())
                            .isEqualTo(ErrorCode.PLUGIN_FORBIDDEN_CALL));
            assertThat(ForbiddenCalls.scanDirectory(plugin.classpath().get(0), List.of("org.example.")))
                    .isEmpty();
        }
    }

    @Test
    void anEmptyScanStillCreatesAManager() {
        PluginRepository.Scan scan = new PluginRepository(dir.resolve("cache")).scan(List.of(dir.resolve("absent")));
        assertThat(scan.directories())
                .containsExactly(
                        new PluginRepository.Directory(dir.resolve("absent").toAbsolutePath(), false, 0));
        try (PluginManager manager = PluginManager.load(scan, PluginManager.Options.defaults())) {
            assertThat(manager.plugins()).isEmpty();
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        try (var files = Files.walk(path)) {
            for (Path p :
                    files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }
}
