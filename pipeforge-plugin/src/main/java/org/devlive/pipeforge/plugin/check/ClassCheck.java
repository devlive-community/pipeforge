/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin.check;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks what a plugin ships: no jar may contain PipeForge API, common or engine classes (for
 * example a shaded copy), and no class may exist twice among the plugin's classes and jars.
 */
public final class ClassCheck {

    /** Packages of classes the engine provides; a plugin must not ship them. */
    public static final List<String> FORBIDDEN_PACKAGES =
            List.of("org/devlive/pipeforge/api/", "org/devlive/pipeforge/common/", "org/devlive/pipeforge/engine/");

    /**
     * Something that holds classes: a classes directory or a jar.
     *
     * @param label how problems name it, e.g. a Maven coordinate or a file name
     */
    public record Source(String label, Path path) {}

    private ClassCheck() {}

    /**
     * Checks the sources. Classes directories are never reported for PipeForge classes (they are
     * the plugin's own code), only jars.
     */
    public static List<String> check(List<Source> sources) throws IOException {
        Map<String, List<String>> owners = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (Source source : sources) {
            Path path = source.path();
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    files.filter(p -> p.toString().endsWith(".class"))
                            .map(p -> path.relativize(p).toString().replace(File.separatorChar, '/'))
                            .forEach(name -> owners.computeIfAbsent(name, k -> new ArrayList<>())
                                    .add(source.label()));
                }
                continue;
            }
            if (!Files.isRegularFile(path)) {
                continue;
            }
            boolean reported = false;
            try (ZipFile zip = new ZipFile(path.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class")
                            || name.startsWith("META-INF/")
                            || name.endsWith("module-info.class")) {
                        continue;
                    }
                    if (!reported && FORBIDDEN_PACKAGES.stream().anyMatch(name::startsWith)) {
                        problems.add(source.label() + " contains PipeForge classes (" + name + ")");
                        reported = true;
                    }
                    owners.computeIfAbsent(name, k -> new ArrayList<>()).add(source.label());
                }
            }
        }
        Map<String, List<String>> duplicates = new LinkedHashMap<>();
        owners.forEach((name, labels) -> {
            if (labels.size() > 1) {
                duplicates.put(name, labels);
            }
        });
        if (!duplicates.isEmpty()) {
            List<String> examples = duplicates.entrySet().stream()
                    .limit(5)
                    .map(e -> e.getKey().replace('/', '.').replaceAll("\\.class$", "") + " in " + e.getValue())
                    .toList();
            problems.add(duplicates.size() + " duplicate class(es), e.g. " + String.join("; ", examples));
        }
        return problems;
    }
}
