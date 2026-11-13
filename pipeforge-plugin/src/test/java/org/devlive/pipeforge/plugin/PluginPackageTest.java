/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.common.plugin.PluginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plugins packed for workers: the same bytes every time, and loadable once unpacked. */
class PluginPackageTest {

    @TempDir
    Path dir;

    /** Packs, unpacks and loads {@code plugin}, then calls its source factory. */
    Object unpackAndCall(PluginPackage.Packed packed, String identifier) throws Exception {
        Path extracted = new PluginRepository(dir.resolve("worker-cache")).extract(packed.file());
        InstalledPlugin unpacked = PluginRepository.read(extracted);
        try (PluginManager manager = new PluginManager(List.of(unpacked), PluginManager.Options.defaults());
                PluginLease lease = manager.acquire(unpacked, "run-1")) {
            SourceFactory factory = lease.factory(PluginType.SOURCE, identifier, SourceFactory.class);
            return factory.getClass().getMethod("libraryVersion").invoke(factory);
        }
    }

    @Test
    void anInstalledPluginPacksToTheSameBytesEveryTime() throws Exception {
        TestPlugins plugins = new TestPlugins(dir);
        Path root = plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("7"), "");
        InstalledPlugin installed = PluginRepository.read(root);

        PluginPackage.Packed first = PluginPackage.pack(installed, dir.resolve("blobs-a"));
        Thread.sleep(1100); // other file times must not change the bytes
        PluginPackage.Packed second = PluginPackage.pack(installed, dir.resolve("blobs-b"));

        assertThat(first.sha256()).hasSize(64).isEqualTo(second.sha256());
        assertThat(first.file().getFileName()).hasToString(first.sha256() + ".zip");
        assertThat(PluginPackage.sha256(first.file())).isEqualTo(first.sha256());
        assertThat(first.size()).isEqualTo(Files.size(first.file()));
        try (ZipFile zip = new ZipFile(first.file().toFile())) {
            assertThat(Collections.list(zip.entries()))
                    .extracting(ZipEntry::getName)
                    .containsExactly("plugin.yaml", "lib/000-alpha-1.0.0.jar", "lib/001-lib-7.jar");
        }
        assertThat(unpackAndCall(first, "alpha-source")).isEqualTo("7");
        // packing again into the same place gives the same file
        assertThat(PluginPackage.pack(installed, dir.resolve("blobs-a"))).isEqualTo(first);
    }

    @Test
    void aDevelopmentModulePacksItsClassesAsAJar() throws Exception {
        TestPlugins plugins = new TestPlugins(dir);
        Path tree = dir.resolve("tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("pom.xml"), "<project/>");
        plugins.module(tree, "connectors", "gamma", "0.1.0", "gamma-source", plugins.library("3"), "");
        InstalledPlugin module = DevPlugins.scan(tree).plugins().get(0);
        assertThat(module.classpath().get(0)).isDirectory();

        PluginPackage.Packed packed = PluginPackage.pack(module, dir.resolve("blobs"));
        try (ZipFile zip = new ZipFile(packed.file().toFile())) {
            assertThat(Collections.list(zip.entries()))
                    .extracting(ZipEntry::getName)
                    .containsExactly("plugin.yaml", "lib/000-classes.jar", "lib/001-lib-3.jar");
        }
        assertThat(unpackAndCall(packed, "gamma-source")).isEqualTo("3");
        assertThat(PluginPackage.pack(module, dir.resolve("again")).sha256()).isEqualTo(packed.sha256());
    }
}
