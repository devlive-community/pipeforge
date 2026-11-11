/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.devlive.pipeforge.api.ApiVersion;
import org.devlive.pipeforge.plugin.DevPlugins;
import org.devlive.pipeforge.plugin.InstalledPlugin;
import org.devlive.pipeforge.plugin.PluginRepository;
import org.devlive.pipeforge.server.TestPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class PluginScanTest {

    @TempDir
    Path dir;

    /** A checkout with a built plugin module that provides the demo components of the test class path. */
    private Path checkout() throws Exception {
        Path tree = Files.createDirectories(dir.resolve("checkout"));
        Files.writeString(tree.resolve("pom.xml"), "<project/>");
        Path server = Files.createDirectories(tree.resolve("pipeforge-server"));
        Files.writeString(server.resolve("pom.xml"), "<project/>");
        Path module = Files.createDirectories(tree.resolve("pipeforge-connectors/connector-demo"));
        Files.createDirectories(module.resolve(DevPlugins.DESCRIPTOR).getParent());
        Files.writeString(
                module.resolve(DevPlugins.DESCRIPTOR),
                """
                id: demo
                name: Demo
                version: 1.0.0
                api-version: "%s"
                provides:
                  - { type: source, identifier: demo-source, class: %s }
                """
                        .formatted(ApiVersion.descriptorForm(), TestPlugin.DemoSource.class.getName()));
        Files.createDirectories(module.resolve(DevPlugins.CLASSES));
        Files.createDirectories(module.resolve(DevPlugins.CLASSPATH_FILE).getParent());
        Files.writeString(module.resolve(DevPlugins.CLASSPATH_FILE), "# no dependencies\n");
        Path unbuilt = Files.createDirectories(tree.resolve("pipeforge-connectors/connector-unbuilt"));
        Files.createDirectories(unbuilt.resolve(DevPlugins.DESCRIPTOR).getParent());
        Files.writeString(
                unbuilt.resolve(DevPlugins.DESCRIPTOR),
                """
                id: unbuilt
                name: Unbuilt
                version: 1.0.0
                api-version: "%s"
                provides:
                  - { type: sink, identifier: unbuilt-sink, class: org.example.Unbuilt }
                """
                        .formatted(ApiVersion.descriptorForm()));
        return tree;
    }

    private ServerProperties.Plugin settings(boolean dev, Path root) {
        Path plugins = TestPlugin.install(dir);
        return new ServerProperties.Plugin(
                List.of(plugins, dir.resolve("absent")), dir.resolve("cache"), new ServerProperties.Dev(dev, root));
    }

    @Test
    void withoutDevelopmentModeOnlyPackagesAreLoaded() throws Exception {
        Path tree = checkout();

        PluginRepository.Scan scan = PluginScan.scan(settings(false, null), tree.resolve("pipeforge-server"));

        assertThat(scan.plugins()).extracting(InstalledPlugin::root).containsExactly(dir.resolve("plugins/demo-1.0.0"));
        assertThat(scan.directories())
                .extracting(PluginRepository.Directory::exists)
                .containsExactly(true, false);
    }

    @Test
    void developmentModeFindsTheCheckoutFromTheWorkingDirectory() throws Exception {
        Path tree = checkout();

        PluginRepository.Scan scan = PluginScan.scan(settings(true, null), tree.resolve("pipeforge-server"));

        // The module replaces the package with the same coordinate.
        assertThat(scan.plugins())
                .extracting(InstalledPlugin::root)
                .containsExactly(tree.resolve("pipeforge-connectors/connector-demo"));
        assertThat(scan.rejected())
                .extracting(r -> r.location().getFileName().toString())
                .containsExactly("broken-1.0.0", "connector-unbuilt");
        assertThat(scan.rejected().get(1).reason())
                .endsWith("build it with ./mvnw compile -pl pipeforge-connectors/connector-unbuilt -am");
    }

    @Test
    void aConfiguredRootWinsOverTheWorkingDirectory() throws Exception {
        Path tree = checkout();
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));

        assertThat(PluginScan.scan(settings(true, tree), elsewhere).plugins())
                .extracting(InstalledPlugin::coordinate)
                .containsExactly("demo@1.0.0");
        assertThat(PluginScan.scan(settings(true, null), elsewhere).plugins())
                .extracting(InstalledPlugin::root)
                .containsExactly(dir.resolve("plugins/demo-1.0.0"));
    }

    @Test
    void aCheckoutWithoutPluginModulesKeepsThePackages() throws Exception {
        Path tree = Files.createDirectories(dir.resolve("empty"));
        Files.writeString(tree.resolve("pom.xml"), "<project/>");

        PluginRepository.Scan scan = PluginScan.scan(settings(true, null), tree);

        assertThat(scan.plugins()).extracting(InstalledPlugin::coordinate).containsExactly("demo@1.0.0");
        assertThat(scan.directories())
                .extracting(PluginRepository.Directory::path)
                .last()
                .isEqualTo(tree);
    }

    @Test
    void anEmptyPluginDirectoryIsReported() {
        ServerProperties.Plugin settings = new ServerProperties.Plugin(
                List.of(dir.resolve("absent")), dir.resolve("cache"), new ServerProperties.Dev(false, null));

        assertThat(PluginScan.scan(settings, dir).plugins()).isEmpty();
    }

    @Test
    void theDevProfileTurnsDevelopmentModeOn() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("dev", new ClassPathResource("application-dev.yaml"));
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addFirst);

        ServerProperties properties = Binder.get(environment)
                .bind("pipeforge", ServerProperties.class)
                .get();

        assertThat(properties.plugin().dev()).isEqualTo(new ServerProperties.Dev(true, null));
        assertThat(properties.plugin().paths()).containsExactly(Path.of("plugins"));
    }
}
