/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.common.error.ErrorCode;

/** Builds real plugin directories in a temporary folder by compiling small sources. */
final class TestPlugins {

    private final Path root;

    TestPlugins(Path root) {
        this.root = root;
    }

    /** A library jar whose class {@code com.example.lib.Greeting} reports {@code version}. */
    Path library(String version) {
        return jar(
                "lib-" + version,
                Map.of(
                        "com/example/lib/Greeting.java",
                        """
                package com.example.lib;
                public final class Greeting {
                    public static String version() { return "%s"; }
                }
                """
                                .formatted(version)),
                List.of());
    }

    /**
     * A plugin with one source factory that reports the version of the library it bundles.
     *
     * @param extraBody additional members of the factory class (e.g. a forbidden call)
     */
    Path plugin(String id, String version, String identifier, Path library, String extraBody) {
        String pkg = pkg(id, version);
        Path pluginJar = jar(
                id + "-" + version,
                Map.of(pkg.replace('.', '/') + "/Factory.java", factory(pkg, identifier, extraBody)),
                List.of(library));
        Path dir = root.resolve("plugins").resolve(id + "-" + version);
        try {
            Files.createDirectories(dir.resolve("lib"));
            Files.copy(pluginJar, dir.resolve("lib").resolve(pluginJar.getFileName()));
            Files.copy(library, dir.resolve("lib").resolve(library.getFileName()));
            Files.writeString(dir.resolve("plugin.yaml"), descriptor(id, version, identifier));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    /**
     * A plugin module of a source tree below {@code tree}, as a Maven build leaves it: the
     * descriptor in src/main/plugin, the compiled factory in target/classes and the dependency list
     * with the library.
     */
    Path module(Path tree, String group, String id, String version, String identifier, Path library, String extraBody) {
        String pkg = pkg(id, version);
        Path classes = compile(
                "module-" + id + "-" + version,
                Map.of(pkg.replace('.', '/') + "/Factory.java", factory(pkg, identifier, extraBody)),
                List.of(library));
        Path module = tree.resolve(group).resolve(id);
        try {
            Files.createDirectories(module.resolve("src/main/plugin"));
            Files.writeString(module.resolve("pom.xml"), "<project/>");
            Files.writeString(module.resolve(DevPlugins.DESCRIPTOR), descriptor(id, version, identifier));
            Path target = Files.createDirectories(module.resolve(DevPlugins.CLASSES));
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path copy = target.resolve(classes.relativize(file));
                    Files.createDirectories(copy.getParent());
                    Files.copy(file, copy);
                }
            }
            Path list = module.resolve(DevPlugins.CLASSPATH_FILE);
            Files.createDirectories(list.getParent());
            Files.writeString(list, "# dependencies\n\n" + library.toAbsolutePath() + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return module;
    }

    private static String pkg(String id, String version) {
        return "org.example." + id.replace('-', '_') + ".v" + version.replace('.', '_');
    }

    private static String descriptor(String id, String version, String identifier) {
        return """
                id: %s
                name: Test
                version: %s
                api-version: "%s"
                provides:
                  - { type: source, identifier: %s, class: %s.Factory }
                """
                .formatted(
                        id,
                        version,
                        org.devlive.pipeforge.api.ApiVersion.descriptorForm(),
                        identifier,
                        pkg(id, version));
    }

    private static String factory(String pkg, String identifier, String extraBody) {
        return """
                package %1$s;
                import org.devlive.pipeforge.api.factory.FactoryContext;
                import org.devlive.pipeforge.api.factory.SourceFactory;
                import org.devlive.pipeforge.api.option.OptionRule;
                import org.devlive.pipeforge.api.source.Source;
                public class Factory implements SourceFactory {
                    public static final Object HELD = new Object();
                    @Override public String identifier() { return "%2$s"; }
                    @Override public OptionRule optionRule() { return OptionRule.builder().build(); }
                    @Override public Source<?, ?> createSource(FactoryContext context) { throw new UnsupportedOperationException(); }
                    public String libraryVersion() { return com.example.lib.Greeting.version(); }
                    public String contextLoader() { return String.valueOf(Thread.currentThread().getContextClassLoader()); }
                    %3$s
                }
                """
                .formatted(pkg, identifier, extraBody);
    }

    private Path compile(String name, Map<String, String> sources, List<Path> classpath) {
        try {
            Path src = Files.createDirectories(root.resolve("src").resolve(name));
            Path out = Files.createDirectories(root.resolve("classes").resolve(name));
            List<String> files = new ArrayList<>();
            for (Map.Entry<String, String> source : sources.entrySet()) {
                Path file = src.resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
                files.add(file.toString());
            }
            List<String> cp = new ArrayList<>(List.of(location(SourceFactory.class), location(ErrorCode.class)));
            classpath.forEach(p -> cp.add(p.toString()));
            List<String> args = new ArrayList<>(List.of(
                    "-d",
                    out.toString(),
                    "-cp",
                    String.join(java.io.File.pathSeparator, cp),
                    "--release",
                    "21",
                    "-proc:none"));
            args.addAll(files);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler.run(null, null, null, args.toArray(String[]::new)) != 0) {
                throw new IllegalStateException("Compilation of " + name + " failed");
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path jar(String name, Map<String, String> sources, List<Path> classpath) {
        Path out = compile(name, sources, classpath);
        try {
            Path jar = root.resolve("jars").resolve(name + ".jar");
            Files.createDirectories(jar.getParent());
            try (OutputStream os = Files.newOutputStream(jar);
                    JarOutputStream zip = new JarOutputStream(os);
                    Stream<Path> classes = Files.walk(out)) {
                for (Path file : classes.filter(Files::isRegularFile).toList()) {
                    zip.putNextEntry(
                            new JarEntry(out.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
