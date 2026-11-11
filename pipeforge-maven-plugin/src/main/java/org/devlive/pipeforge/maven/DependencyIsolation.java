/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Isolation rules for plugin dependencies:
 *
 * <ul>
 *   <li>{@code pipeforge-api} and {@code pipeforge-common} are {@code provided}: the engine supplies
 *       them parent-first, so a plugin never ships its own copy;
 *   <li>no other PipeForge module (engine, core, plugin loader) may be a dependency;
 *   <li>no jar may contain PipeForge API or engine classes (for example a shaded copy);
 *   <li>no class may exist twice among the plugin's own classes and runtime jars.
 * </ul>
 */
public final class DependencyIsolation {

    /** PipeForge modules a plugin may depend on, with {@code provided} scope. */
    public static final Set<String> PROVIDED_MODULES = Set.of("pipeforge-api", "pipeforge-common");

    static final String GROUP = "org.devlive.pipeforge";

    private static final List<String> FORBIDDEN_PACKAGES =
            List.of("org/devlive/pipeforge/api/", "org/devlive/pipeforge/common/", "org/devlive/pipeforge/engine/");

    /** A resolved dependency. */
    public record Dependency(String groupId, String artifactId, String version, String scope, File file) {
        String coordinate() {
            return groupId + ":" + artifactId + ":" + version;
        }

        boolean shipped() {
            return "compile".equals(scope) || "runtime".equals(scope);
        }
    }

    private DependencyIsolation() {}

    /** Checks the dependency scopes and PipeForge modules. */
    public static List<String> checkScopes(List<Dependency> dependencies) {
        List<String> problems = new ArrayList<>();
        for (Dependency dependency : dependencies) {
            if (!GROUP.equals(dependency.groupId()) || "test".equals(dependency.scope())) {
                continue;
            }
            if (PROVIDED_MODULES.contains(dependency.artifactId())) {
                if (!"provided".equals(dependency.scope())) {
                    problems.add(dependency.coordinate() + " must have provided scope; the engine supplies it");
                }
            } else {
                problems.add("plugins must not depend on " + dependency.coordinate()
                        + "; only pipeforge-api (provided) is allowed");
            }
        }
        return problems;
    }

    /** The dependencies that go into the plugin package. */
    public static List<Dependency> shipped(List<Dependency> dependencies) {
        return dependencies.stream().filter(Dependency::shipped).toList();
    }

    /**
     * Checks the classes of the plugin ({@code classesDir}) and of the shipped jars for PipeForge
     * classes and duplicates.
     */
    public static List<String> checkClasses(Path classesDir, List<Dependency> shipped) throws IOException {
        Map<String, List<String>> owners = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        if (Files.isDirectory(classesDir)) {
            try (Stream<Path> files = Files.walk(classesDir)) {
                files.filter(p -> p.toString().endsWith(".class"))
                        .map(p -> classesDir.relativize(p).toString().replace(File.separatorChar, '/'))
                        .forEach(name -> owners.computeIfAbsent(name, k -> new ArrayList<>())
                                .add("plugin classes"));
            }
        }
        for (Dependency dependency : shipped) {
            if (!dependency.file().isFile()) {
                continue;
            }
            boolean reported = false;
            try (ZipFile zip = new ZipFile(dependency.file())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class")
                            || name.startsWith("META-INF/")
                            || name.endsWith("module-info.class")) {
                        continue;
                    }
                    if (!reported && FORBIDDEN_PACKAGES.stream().anyMatch(name::startsWith)) {
                        problems.add(dependency.coordinate() + " contains PipeForge classes (" + name + ")");
                        reported = true;
                    }
                    owners.computeIfAbsent(name, k -> new ArrayList<>()).add(dependency.coordinate());
                }
            }
        }
        Map<String, List<String>> duplicates = new LinkedHashMap<>();
        owners.forEach((name, sources) -> {
            if (sources.size() > 1) {
                duplicates.put(name, sources);
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
