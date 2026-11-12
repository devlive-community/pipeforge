/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;
import org.devlive.pipeforge.plugin.check.ClassCheck;
import org.devlive.pipeforge.plugin.check.PackageCheck;
import org.devlive.pipeforge.plugin.check.PluginDocs;
import org.devlive.pipeforge.plugin.check.PluginInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageCheckTest {

    @TempDir
    Path dir;

    private static final ClassLoader API = PackageCheckTest.class.getClassLoader();

    /** A package that passes: a compiled factory, a license and current documentation data. */
    private Path good(TestPlugins plugins, String extraBody) throws Exception {
        Path root = plugins.plugin("alpha", "1.0.0", "alpha-source", plugins.library("1"), extraBody);
        Path descriptor = root.resolve(PluginDescriptor.FILE_NAME);
        Files.writeString(descriptor, Files.readString(descriptor) + "license: MIT\n");
        writeDocs(root);
        return root;
    }

    private static void writeDocs(Path root) throws Exception {
        PluginDescriptor descriptor = PluginRepository.read(root).descriptor();
        List<java.net.URL> urls = new java.util.ArrayList<>();
        for (Path jar : PluginRepository.read(root).classpath()) {
            urls.add(jar.toUri().toURL());
        }
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new), API)) {
            PluginInspector.Result inspected = PluginInspector.inspect(descriptor, loader);
            PluginDocs.write(PluginDocs.model(descriptor, inspected.components()), root.resolve("docs"));
        }
    }

    private static Map<String, List<String>> problems(PackageCheck.Result result) {
        Map<String, List<String>> problems = new java.util.LinkedHashMap<>();
        result.checks().forEach(c -> problems.put(c.name(), c.problems()));
        return problems;
    }

    private static Path zip(Path root, Path zip) throws Exception {
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream archive = new ZipOutputStream(out);
                Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                archive.putNextEntry(
                        new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, archive);
                archive.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void aGoodPackagePassesEveryCheck() throws Exception {
        Path root = good(new TestPlugins(dir), "");

        PackageCheck.Result result = PackageCheck.check(root, dir.resolve("cache"), API);

        assertThat(result.plugin()).isEqualTo("alpha@1.0.0");
        assertThat(result.checks()).extracting(PackageCheck.Check::name).containsExactlyElementsOf(PackageCheck.CHECKS);
        assertThat(problems(result).values()).allMatch(List::isEmpty);
        assertThat(result.passed()).isTrue();
        assertThat(result.components())
                .extracting(c -> c.component().identifier())
                .containsExactly("alpha-source");

        PackageCheck.Result zipped = PackageCheck.check(zip(root, dir.resolve("alpha.zip")), dir.resolve("cache"), API);
        assertThat(zipped.passed()).isTrue();
    }

    @Test
    void reportsAMissingLicenseAndAnotherApi() throws Exception {
        Path root =
                new TestPlugins(dir).plugin("alpha", "1.0.0", "alpha-source", new TestPlugins(dir).library("1"), "");
        Path descriptor = root.resolve(PluginDescriptor.FILE_NAME);
        Files.writeString(
                descriptor, Files.readString(descriptor).replaceAll("api-version: \".*\"", "api-version: \"99.x\""));

        PackageCheck.Result result = PackageCheck.check(root, dir.resolve("cache"), API);

        assertThat(problems(result).get("descriptor"))
                .containsExactly(
                        "plugin.yaml declares no license",
                        "built for API 99.x, this engine provides "
                                + org.devlive.pipeforge.api.ApiVersion.descriptorForm());
        assertThat(problems(result).get("docs")).singleElement().asString().contains("docs/alpha.json is missing");
        assertThat(result.passed()).isFalse();
    }

    @Test
    void findsForbiddenCallsShippedApiClassesAndDuplicates() throws Exception {
        TestPlugins plugins = new TestPlugins(dir);
        Path root = good(plugins, "public void stop() { System.exit(1); }");
        Path lib = root.resolve("lib");
        // A shaded copy of an API class, and the library twice under another name.
        try (OutputStream out = Files.newOutputStream(lib.resolve("shaded.jar"));
                ZipOutputStream jar = new ZipOutputStream(out)) {
            jar.putNextEntry(new ZipEntry("org/devlive/pipeforge/api/row/Row.class"));
            jar.write(new byte[] {1});
            jar.closeEntry();
        }
        Files.copy(lib.resolve("lib-1.jar"), lib.resolve("lib-copy.jar"));

        PackageCheck.Result result = PackageCheck.check(root, dir.resolve("cache"), API);

        assertThat(problems(result).get("isolation"))
                .contains("shaded.jar contains PipeForge classes (org/devlive/pipeforge/api/row/Row.class)")
                .anyMatch(p -> p.startsWith(
                        "1 duplicate class(es), e.g. com.example.lib.Greeting in [lib-1.jar, lib-copy.jar]"));
        // The shaded class is not a class file at all: reported, not a crash.
        assertThat(problems(result).get("forbidden-calls"))
                .anySatisfy(p ->
                        assertThat(p).contains("calls java.lang.System.exit").contains("alpha-1.0.0.jar"))
                .anySatisfy(p -> assertThat(p).startsWith("shaded.jar has class files that cannot be read: "));
    }

    @Test
    void findsComponentsThatDoNotLoadAndStaleDocumentation() throws Exception {
        Path root = good(new TestPlugins(dir), "");
        Path docs = root.resolve("docs/alpha.json");
        Files.writeString(docs, Files.readString(docs).replace("\"Test\"", "\"Renamed\""), StandardCharsets.UTF_8);

        assertThat(problems(PackageCheck.check(root, dir.resolve("cache"), API)).get("docs"))
                .singleElement()
                .asString()
                .startsWith("docs/alpha.json is stale: ");

        Path descriptor = root.resolve(PluginDescriptor.FILE_NAME);
        Files.writeString(
                descriptor, Files.readString(descriptor).replaceAll("class: [\\w.]+", "class: org.example.Missing"));
        PackageCheck.Result result = PackageCheck.check(root, dir.resolve("cache"), API);
        assertThat(problems(result).get("components"))
                .singleElement()
                .asString()
                .contains("org.example.Missing");
        assertThat(problems(result).get("docs")).containsExactly("not checked: the components do not load");
        assertThat(result.components()).isEmpty();
    }

    @Test
    void packagesWithoutADescriptorOrJars() throws Exception {
        Path empty = Files.createDirectories(dir.resolve("empty"));
        PackageCheck.Result none = PackageCheck.check(empty, dir.resolve("cache"), API);
        assertThat(none.plugin()).isNull();
        assertThat(problems(none)).containsExactly(Map.entry("descriptor", List.of("the package has no plugin.yaml")));

        Files.writeString(empty.resolve(PluginDescriptor.FILE_NAME), "id: broken\n");
        PackageCheck.Result invalid = PackageCheck.check(empty, dir.resolve("cache"), API);
        assertThat(problems(invalid).get("descriptor")).isNotEmpty();
        assertThat(invalid.checks()).hasSize(1);

        Files.writeString(
                empty.resolve(PluginDescriptor.FILE_NAME),
                "id: nojars\nname: No jars\nversion: 1.0.0\napi-version: \""
                        + org.devlive.pipeforge.api.ApiVersion.descriptorForm()
                        + "\"\nlicense: MIT\nprovides:\n  - { type: source, identifier: x, class: org.example.X }\n");
        PackageCheck.Result noJars = PackageCheck.check(empty, dir.resolve("cache"), API);
        assertThat(noJars.plugin()).isEqualTo("nojars@1.0.0");
        assertThat(problems(noJars).get("isolation")).containsExactly("nojars@1.0.0 has no jars in lib/");
    }

    @Test
    void classesDirectoriesAreNotReportedForPipeForgeClasses() throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes/org/devlive/pipeforge/api"));
        Files.writeString(classes.resolve("Own.class"), "x");
        List<String> problems = ClassCheck.check(List.of(
                new ClassCheck.Source("plugin classes", dir.resolve("classes")),
                new ClassCheck.Source("absent", dir.resolve("absent.jar"))));
        assertThat(problems).isEmpty();
    }
}
