/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.devlive.pipeforge.plugin.DevPlugins;
import org.devlive.pipeforge.plugin.PluginManager;
import org.devlive.pipeforge.plugin.PluginRepository;
import org.jspecify.annotations.Nullable;

/**
 * One invocation: the global options resolved against the environment, the output, and the plugins,
 * loaded on first use and closed at the end.
 */
public final class Session implements AutoCloseable {

    private final Root root;
    private final Environment environment;
    private final Messages messages;
    private @Nullable PluginManager plugins;
    private PluginRepository.@Nullable Scan scan;

    Session(Root root, Environment environment) {
        this.root = root;
        this.environment = environment;
        this.messages =
                Messages.of(Messages.resolve(root.lang, environment.variable("PIPEFORGE_LANG"), Locale.getDefault()));
    }

    public Environment environment() {
        return environment;
    }

    public Messages messages() {
        return messages;
    }

    public String language() {
        return messages.language();
    }

    public Output.Format format() {
        return root.output;
    }

    public boolean table() {
        return root.output == Output.Format.TABLE;
    }

    public boolean quiet() {
        return root.quiet;
    }

    public boolean verbose() {
        return root.verbose;
    }

    public boolean color() {
        return environment.interactive() && !root.noColor && environment.variable("NO_COLOR") == null;
    }

    public PrintStream out() {
        return environment.out();
    }

    public PrintStream err() {
        return environment.err();
    }

    /** Prints a result: the table text, or the structured value as JSON or YAML. */
    public void print(Object structured, String table) {
        out().print(table() ? table : Output.render(structured, format()));
        out().flush();
    }

    /** Prints a message about the work, not a result: to the standard error, unless quiet. */
    public void note(String text) {
        if (!quiet()) {
            err().println(text);
        }
    }

    /**
     * The plugin directories: --plugin-path, else PIPEFORGE_PLUGIN_PATH, else plugins/ of the
     * installation, else ./plugins.
     */
    public List<Path> pluginDirectories() {
        List<Path> paths = new ArrayList<>();
        if (!root.pluginPaths.isEmpty()) {
            for (Path path : root.pluginPaths) {
                for (String part : path.toString().split(File.pathSeparator, -1)) {
                    if (!part.isBlank()) {
                        paths.add(environment.workingDirectory().resolve(part).normalize());
                    }
                }
            }
            return paths;
        }
        String variable = environment.variable("PIPEFORGE_PLUGIN_PATH");
        if (variable != null) {
            for (String part : variable.split(File.pathSeparator, -1)) {
                if (!part.isBlank()) {
                    paths.add(environment.workingDirectory().resolve(part).normalize());
                }
            }
            return paths;
        }
        Path home = environment.home();
        paths.add(
                home != null
                        ? home.resolve("plugins")
                        : environment.workingDirectory().resolve("plugins"));
        return paths;
    }

    /** The root of the source checkout for --dev, or empty without --dev. */
    public Optional<Path> devRoot() {
        if (root.dev == null) {
            return Optional.empty();
        }
        if (!root.dev.isBlank()) {
            return Optional.of(environment.workingDirectory().resolve(root.dev).normalize());
        }
        return DevPlugins.findRoot(environment.workingDirectory());
    }

    /** Whether --dev was given. */
    public boolean dev() {
        return root.dev != null;
    }

    /** Where zipped plugins are extracted. */
    public Path pluginCache() {
        return environment.stateDirectory().resolve("cache").resolve("plugins");
    }

    /** The installed plugins, and with --dev those of the source checkout. */
    public synchronized PluginManager plugins() {
        if (plugins == null) {
            PluginRepository.Scan installed = new PluginRepository(pluginCache()).scan(pluginDirectories());
            Optional<Path> dev = devRoot();
            PluginRepository.Scan all = dev.map(r -> DevPlugins.overlay(installed, DevPlugins.scan(r)))
                    .orElse(installed);
            scan = all;
            plugins = PluginManager.load(all, PluginManager.Options.defaults());
        }
        return plugins;
    }

    /** What the plugin scan found, after {@link #plugins()}. */
    public PluginRepository.Scan scan() {
        plugins();
        return java.util.Objects.requireNonNull(scan);
    }

    @Override
    public synchronized void close() {
        if (plugins != null) {
            plugins.close();
            plugins = null;
        }
    }
}
