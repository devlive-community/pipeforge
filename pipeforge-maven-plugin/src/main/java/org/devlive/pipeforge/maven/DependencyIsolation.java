/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.devlive.pipeforge.plugin.check.ClassCheck;

/**
 * Isolation rules for plugin dependencies:
 *
 * <ul>
 *   <li>{@code pipeforge-api} and {@code pipeforge-common} are {@code provided}: the engine supplies
 *       them parent-first, so a plugin never ships its own copy;
 *   <li>a library module, shared connector code marked with {@value #LIBRARY_MARKER}, is shipped in
 *       the plugin's {@code lib/}, so it has compile or runtime scope;
 *   <li>no other PipeForge module (engine, core, plugin loader) may be a dependency;
 *   <li>no jar may contain PipeForge API or engine classes (for example a shaded copy);
 *   <li>no class may exist twice among the plugin's own classes and runtime jars.
 * </ul>
 */
public final class DependencyIsolation {

    /** PipeForge modules a plugin may depend on, with {@code provided} scope. */
    public static final Set<String> PROVIDED_MODULES = Set.of("pipeforge-api", "pipeforge-common");

    static final String GROUP = "org.devlive.pipeforge";

    /** The resource that marks a PipeForge module as a library plugins ship, e.g. {@code connector-jdbc}. */
    public static final String LIBRARY_MARKER = "META-INF/pipeforge/library.properties";

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
            } else if (library(dependency)) {
                if (!dependency.shipped()) {
                    problems.add(
                            dependency.coordinate()
                                    + " is a library module; it must have compile or runtime scope so that the plugin ships it");
                }
            } else {
                problems.add("plugins must not depend on " + dependency.coordinate()
                        + "; only pipeforge-api, pipeforge-common (provided) and library modules are allowed");
            }
        }
        return problems;
    }

    /** Whether the dependency, a jar or a classes directory, holds {@link #LIBRARY_MARKER}. */
    static boolean library(Dependency dependency) {
        File file = dependency.file();
        if (file == null || !file.exists()) {
            return false;
        }
        if (file.isDirectory()) {
            return new File(file, LIBRARY_MARKER).isFile();
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
            return zip.getEntry(LIBRARY_MARKER) != null;
        } catch (IOException e) {
            return false;
        }
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
        List<ClassCheck.Source> sources = new ArrayList<>();
        sources.add(new ClassCheck.Source("plugin classes", classesDir));
        for (Dependency dependency : shipped) {
            sources.add(new ClassCheck.Source(
                    dependency.coordinate(), dependency.file().toPath()));
        }
        return ClassCheck.check(sources);
    }
}
