/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development mode: finds the plugin modules of a source tree and loads them from their build
 * output, so that a server started from an IDE uses the plugins of the checkout without packaging
 * them.
 *
 * <p>A plugin module is a directory two levels below the root, such as {@code
 * pipeforge-connectors/connector-fake}, with {@code src/main/plugin/plugin.yaml}. Its class path is
 * {@code target/classes}, which the IDE updates on every build, followed by the jars listed in
 * {@code target/pipeforge-plugin/classpath.txt}, which the {@code dev-classpath} goal of
 * pipeforge-maven-plugin writes when Maven compiles the module. Each plugin still gets its own
 * class loader, as in production.
 */
public final class DevPlugins {

    /** The descriptor of a plugin module, relative to the module. */
    public static final String DESCRIPTOR = "src/main/plugin/" + PluginDescriptor.FILE_NAME;

    /** The compiled classes of a plugin module, relative to the module. */
    public static final String CLASSES = "target/classes";

    /** The dependency list of a plugin module, relative to the module. */
    public static final String CLASSPATH_FILE = "target/pipeforge-plugin/classpath.txt";

    private static final Logger LOG = LoggerFactory.getLogger(DevPlugins.class);
    private static final String BUILD_FILE = "pom.xml";

    private DevPlugins() {}

    /**
     * Finds the root of the source tree that contains a directory: the outermost directory of the
     * chain of directories with a pom.xml that starts at the nearest one.
     *
     * @return the root, or empty when neither the directory nor a parent has a pom.xml
     */
    public static Optional<Path> findRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve(BUILD_FILE))) {
            current = current.getParent();
        }
        if (current == null) {
            return Optional.empty();
        }
        Path parent = current.getParent();
        while (parent != null && Files.isRegularFile(parent.resolve(BUILD_FILE))) {
            current = parent;
            parent = current.getParent();
        }
        return Optional.of(current);
    }

    /** Scans the plugin modules of a source tree. */
    public static PluginRepository.Scan scan(Path root) {
        Path directory = root.toAbsolutePath().normalize();
        List<InstalledPlugin> plugins = new ArrayList<>();
        List<PluginRepository.Rejected> rejected = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return new PluginRepository.Scan(
                    plugins, rejected, List.of(new PluginRepository.Directory(directory, false, 0)));
        }
        List<Path> modules;
        try {
            modules = modules(directory);
        } catch (IOException e) {
            rejected.add(new PluginRepository.Rejected(directory, "cannot be listed: " + e.getMessage()));
            modules = List.of();
        }
        for (Path module : modules) {
            try {
                InstalledPlugin plugin = read(directory, module);
                plugins.add(plugin);
                LOG.info("Development plugin {} from {}", plugin.coordinate(), module);
            } catch (IOException | IllegalArgumentException e) {
                rejected.add(new PluginRepository.Rejected(module, String.valueOf(e.getMessage())));
            }
        }
        return new PluginRepository.Scan(
                plugins, rejected, List.of(new PluginRepository.Directory(directory, true, plugins.size())));
    }

    /**
     * Combines installed and development plugins; a development plugin replaces an installed one
     * with the same id and version.
     */
    public static PluginRepository.Scan overlay(PluginRepository.Scan installed, PluginRepository.Scan development) {
        Set<String> coordinates = new HashSet<>();
        development.plugins().forEach(p -> coordinates.add(p.coordinate()));
        List<InstalledPlugin> plugins = new ArrayList<>(development.plugins());
        for (InstalledPlugin plugin : installed.plugins()) {
            if (coordinates.contains(plugin.coordinate())) {
                LOG.info("Using the development build of {} instead of {}", plugin.coordinate(), plugin.root());
            } else {
                plugins.add(plugin);
            }
        }
        List<PluginRepository.Rejected> rejected = new ArrayList<>(installed.rejected());
        rejected.addAll(development.rejected());
        List<PluginRepository.Directory> directories = new ArrayList<>(installed.directories());
        directories.addAll(development.directories());
        return new PluginRepository.Scan(plugins, rejected, directories);
    }

    private static List<Path> modules(Path root) throws IOException {
        List<Path> modules = new ArrayList<>();
        for (Path group : directories(root)) {
            for (Path module : directories(group)) {
                if (Files.isRegularFile(module.resolve(DESCRIPTOR))) {
                    modules.add(module);
                }
            }
        }
        return modules;
    }

    private static List<Path> directories(Path parent) throws IOException {
        try (Stream<Path> list = Files.list(parent)) {
            return list.filter(Files::isDirectory)
                    .filter(p -> !skipped(p.getFileName()))
                    .sorted()
                    .toList();
        }
    }

    private static boolean skipped(@Nullable Path name) {
        String text = String.valueOf(name);
        return text.startsWith(".") || text.equals("target") || text.equals("node_modules");
    }

    private static InstalledPlugin read(Path root, Path module) throws IOException {
        PluginDescriptor descriptor = PluginDescriptor.parse(
                        Files.readString(module.resolve(DESCRIPTOR), StandardCharsets.UTF_8))
                .orElseThrow();
        String build = "build it with ./mvnw compile -pl "
                + root.relativize(module).toString().replace('\\', '/') + " -am";
        Path classes = module.resolve(CLASSES);
        if (!Files.isDirectory(classes)) {
            throw new IllegalArgumentException(
                    descriptor.coordinate() + " is not compiled: " + classes + " is missing; " + build);
        }
        Path list = module.resolve(CLASSPATH_FILE);
        if (!Files.isRegularFile(list)) {
            throw new IllegalArgumentException(
                    descriptor.coordinate() + " has no dependency list " + list + "; " + build);
        }
        List<Path> classpath = new ArrayList<>();
        classpath.add(classes);
        for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            Path dependency = Path.of(entry);
            if (!Files.exists(dependency)) {
                throw new IllegalArgumentException(
                        descriptor.coordinate() + " misses its dependency " + dependency + "; " + build);
            }
            classpath.add(dependency);
        }
        return new InstalledPlugin(descriptor, module, classpath);
    }
}
