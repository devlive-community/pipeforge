/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a plugin package:
 *
 * <pre>
 * plugin.yaml
 * lib/            plugin jar and its runtime dependencies
 * docs/           generated documentation data
 * native-options/ catalog of native client options (optional)
 * icon.svg        (optional)
 * </pre>
 *
 * Entries are sorted and carry a fixed timestamp, so the same input gives the same bytes.
 */
public final class PluginArchive {

    private static final FileTime EPOCH = FileTime.fromMillis(1_767_225_600_000L);

    private PluginArchive() {}

    /**
     * Writes the archive.
     *
     * @param target the zip file to create
     * @param descriptor the plugin.yaml file
     * @param libs jars for lib/
     * @param docs files for docs/
     * @param extraDirs directories copied below their names (native-options, ...), if they exist
     * @param icon the icon, if it exists
     */
    public static void write(
            Path target, Path descriptor, List<Path> libs, List<Path> docs, List<Path> extraDirs, Path icon)
            throws IOException {
        Map<String, Path> entries = new TreeMap<>();
        entries.put("plugin.yaml", descriptor);
        for (Path lib : libs) {
            put(entries, "lib/" + lib.getFileName(), lib);
        }
        for (Path doc : docs) {
            put(entries, "docs/" + doc.getFileName(), doc);
        }
        for (Path dir : extraDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = dir.getFileName() + "/"
                            + dir.relativize(file).toString().replace('\\', '/');
                    put(entries, name, file);
                }
            }
        }
        if (Files.isRegularFile(icon)) {
            entries.put("icon.svg", icon);
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(target);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, Path> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setLastModifiedTime(EPOCH);
                zip.putNextEntry(zipEntry);
                Files.copy(entry.getValue(), zip);
                zip.closeEntry();
            }
        }
    }

    private static void put(Map<String, Path> entries, String name, Path file) {
        Path previous = entries.putIfAbsent(name, file);
        if (previous != null && !previous.equals(file)) {
            throw new IllegalArgumentException(
                    "Two files would be packaged as " + name + ": " + previous + ", " + file);
        }
    }
}
