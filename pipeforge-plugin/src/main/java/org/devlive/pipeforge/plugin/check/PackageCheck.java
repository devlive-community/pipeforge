/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin.check;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.ApiVersion;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.devlive.pipeforge.plugin.ForbiddenCalls;
import org.devlive.pipeforge.plugin.InstalledPlugin;
import org.devlive.pipeforge.plugin.PluginRepository;
import org.jspecify.annotations.Nullable;

/**
 * Checks a built plugin package, a zip or its extracted directory, the way the engine will use it:
 *
 * <ul>
 *   <li>{@code descriptor}: plugin.yaml is valid, declares a license and the API major version of this engine;
 *   <li>{@code isolation}: lib/ holds jars without PipeForge API or engine classes and without duplicates;
 *   <li>{@code forbidden-calls}: no class calls a JVM-wide operation such as System.exit;
 *   <li>{@code components}: every component of plugin.yaml loads, is a factory of its type and reports its identifier;
 *   <li>{@code docs}: docs/&lt;id&gt;.json exists and equals what the components export.
 * </ul>
 */
public final class PackageCheck {

    /** The names of the checks, in order. */
    public static final List<String> CHECKS =
            List.of("descriptor", "isolation", "forbidden-calls", "components", "docs");

    /** One check and the problems it found; no problems means it passed. */
    public record Check(String name, List<String> problems) {
        public Check {
            problems = List.copyOf(problems);
        }

        public boolean passed() {
            return problems.isEmpty();
        }
    }

    /**
     * The outcome.
     *
     * @param plugin {@code id@version}, or null when plugin.yaml cannot be read
     * @param components the components that loaded, with their capabilities
     */
    public record Result(@Nullable String plugin, List<Check> checks, List<PluginInspector.Inspected> components) {
        public Result {
            checks = List.copyOf(checks);
            components = List.copyOf(components);
        }

        public boolean passed() {
            return checks.stream().allMatch(Check::passed);
        }
    }

    private PackageCheck() {}

    /**
     * Checks a package.
     *
     * @param cache where a zip is extracted
     * @param parent the class loader that provides the connector API, as the engine does
     */
    public static Result check(Path location, Path cache, ClassLoader parent) throws IOException {
        Path root = Files.isDirectory(location) ? location : new PluginRepository(cache).extract(location);
        List<Check> checks = new ArrayList<>();
        Path file = root.resolve(PluginDescriptor.FILE_NAME);
        if (!Files.isRegularFile(file)) {
            checks.add(new Check("descriptor", List.of("the package has no " + PluginDescriptor.FILE_NAME)));
            return new Result(null, checks, List.of());
        }
        PluginDescriptor.ParseResult parsed = PluginDescriptor.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!parsed.isValid()) {
            checks.add(new Check("descriptor", parsed.problems()));
            return new Result(null, checks, List.of());
        }
        PluginDescriptor descriptor = parsed.descriptor();
        checks.add(new Check("descriptor", descriptorProblems(descriptor)));
        InstalledPlugin plugin;
        try {
            plugin = PluginRepository.read(root);
        } catch (IllegalArgumentException e) {
            checks.add(new Check("isolation", List.of(String.valueOf(e.getMessage()))));
            return new Result(descriptor.coordinate(), checks, List.of());
        }
        List<ClassCheck.Source> sources = new ArrayList<>();
        plugin.classpath().forEach(jar -> sources.add(new ClassCheck.Source(String.valueOf(jar.getFileName()), jar)));
        checks.add(new Check("isolation", ClassCheck.check(sources)));
        List<String> forbidden = new ArrayList<>();
        for (Path jar : plugin.classpath()) {
            try {
                for (ForbiddenCalls.Violation violation : ForbiddenCalls.scan(jar, List.of())) {
                    forbidden.add(violation.location() + " calls " + violation.call() + " (" + jar.getFileName() + ")");
                }
            } catch (IOException e) {
                forbidden.add(jar.getFileName() + " has class files that cannot be read: " + e);
            }
        }
        checks.add(new Check("forbidden-calls", forbidden));
        List<URL> urls = new ArrayList<>();
        for (Path jar : plugin.classpath()) {
            urls.add(jar.toUri().toURL());
        }
        PluginInspector.Result inspected;
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), parent)) {
            inspected = PluginInspector.inspect(descriptor, loader);
            checks.add(new Check("components", inspected.problems()));
            checks.add(new Check("docs", docsProblems(descriptor, inspected, root)));
        }
        return new Result(descriptor.coordinate(), checks, inspected.components());
    }

    private static List<String> descriptorProblems(PluginDescriptor descriptor) {
        List<String> problems = new ArrayList<>();
        if (descriptor.license() == null || descriptor.license().isBlank()) {
            problems.add("plugin.yaml declares no license");
        }
        if (descriptor.apiMajorVersion() != ApiVersion.major()) {
            problems.add("built for API " + descriptor.apiVersion() + ", this engine provides "
                    + ApiVersion.descriptorForm());
        }
        return problems;
    }

    private static List<String> docsProblems(PluginDescriptor descriptor, PluginInspector.Result inspected, Path root)
            throws IOException {
        if (!inspected.problems().isEmpty()) {
            return List.of("not checked: the components do not load");
        }
        Path docs = root.resolve("docs");
        if (!Files.isRegularFile(docs.resolve(descriptor.id() + ".json"))) {
            return List.of(
                    "docs/" + descriptor.id() + ".json is missing; build the package with pipeforge-maven-plugin");
        }
        Map<String, Object> model = PluginDocs.model(descriptor, inspected.components());
        String difference = PluginDocs.stale(model, docs);
        return difference == null ? List.of() : List.of("docs/" + descriptor.id() + ".json is stale: " + difference);
    }
}
