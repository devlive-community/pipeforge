/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;

/**
 * Finds installed plugins. A plugin directory holds plugin packages either as zip files or
 * extracted into sub-directories; zips are extracted into a cache keyed by their content hash, so
 * an updated package never reuses stale files.
 */
public final class PluginRepository {

    /** A directory entry that is not a valid plugin, with the reason. */
    public record Rejected(Path location, String reason) {}

    /**
     * A directory that was searched for plugins.
     *
     * @param path the absolute path
     * @param exists whether the directory exists
     * @param plugins the number of plugins found in it
     */
    public record Directory(Path path, boolean exists, int plugins) {}

    /** Plugins found, entries that were rejected and the directories that were searched. */
    public record Scan(List<InstalledPlugin> plugins, List<Rejected> rejected, List<Directory> directories) {
        public Scan {
            plugins = List.copyOf(plugins);
            rejected = List.copyOf(rejected);
            directories = List.copyOf(directories);
        }
    }

    private final Path cacheDir;

    /** @param cacheDir where zipped plugins are extracted */
    public PluginRepository(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** Scans directories for plugins; missing directories are skipped. */
    public Scan scan(List<Path> directories) {
        List<InstalledPlugin> plugins = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        List<Directory> searched = new ArrayList<>();
        for (Path configured : directories) {
            Path directory = configured.toAbsolutePath().normalize();
            if (!Files.isDirectory(directory)) {
                searched.add(new Directory(directory, false, 0));
                continue;
            }
            int before = plugins.size();
            List<Path> entries;
            try (Stream<Path> list = Files.list(directory)) {
                entries = list.sorted().toList();
            } catch (IOException e) {
                rejected.add(new Rejected(directory, "cannot be listed: " + e.getMessage()));
                searched.add(new Directory(directory, true, 0));
                continue;
            }
            for (Path entry : entries) {
                try {
                    if (Files.isDirectory(entry) && Files.isRegularFile(entry.resolve(PluginDescriptor.FILE_NAME))) {
                        plugins.add(read(entry));
                    } else if (String.valueOf(entry.getFileName()).endsWith(".zip")) {
                        plugins.add(read(extract(entry)));
                    }
                } catch (IOException | IllegalArgumentException e) {
                    rejected.add(new Rejected(entry, String.valueOf(e.getMessage())));
                }
            }
            searched.add(new Directory(directory, true, plugins.size() - before));
        }
        return new Scan(plugins, rejected, searched);
    }

    /** Reads an extracted plugin directory. */
    public static InstalledPlugin read(Path root) throws IOException {
        Path file = root.resolve(PluginDescriptor.FILE_NAME);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("missing " + PluginDescriptor.FILE_NAME);
        }
        PluginDescriptor descriptor = PluginDescriptor.parse(Files.readString(file, StandardCharsets.UTF_8))
                .orElseThrow();
        Path lib = root.resolve("lib");
        List<Path> jars = new ArrayList<>();
        if (Files.isDirectory(lib)) {
            try (Stream<Path> files = Files.list(lib)) {
                files.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .forEach(jars::add);
            }
        }
        if (jars.isEmpty()) {
            throw new IllegalArgumentException(descriptor.coordinate() + " has no jars in lib/");
        }
        return new InstalledPlugin(descriptor, root, jars);
    }

    /** Extracts a plugin zip into the cache and returns the directory. */
    Path extract(Path zip) throws IOException {
        Path target = cacheDir.resolve(hash(zip));
        if (Files.isRegularFile(target.resolve(PluginDescriptor.FILE_NAME))) {
            return target;
        }
        Path partial = Files.createDirectories(cacheDir).resolve(target.getFileName() + ".partial");
        deleteRecursively(partial);
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path out = partial.resolve(entry.getName()).normalize();
                if (!out.startsWith(partial)) {
                    throw new IllegalArgumentException("zip entry escapes the plugin directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, out);
                }
            }
        }
        try {
            Files.move(partial, target);
        } catch (IOException e) {
            deleteRecursively(partial);
            if (!Files.isDirectory(target)) {
                throw e;
            }
        }
        return target;
    }

    static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path p :
                    files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }
}
