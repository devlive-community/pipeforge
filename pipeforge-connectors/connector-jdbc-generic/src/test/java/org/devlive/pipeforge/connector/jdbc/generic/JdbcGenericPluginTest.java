/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc.generic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.common.plugin.PluginType;
import org.devlive.pipeforge.plugin.DevPlugins;
import org.devlive.pipeforge.plugin.InstalledPlugin;
import org.devlive.pipeforge.plugin.PluginLease;
import org.devlive.pipeforge.plugin.PluginManager;
import org.devlive.pipeforge.plugin.PluginRepository;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.TestkitEngine;
import org.devlive.pipeforge.testkit.TestkitExtension;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** The plugin as a server loads it: from this module, with the driver a user installs as a driver plugin. */
@ExtendWith(TestkitExtension.class)
class JdbcGenericPluginTest {

    @TempDir
    Path dir;

    private static InstalledPlugin jdbcPlugin() {
        Path root = DevPlugins.findRoot(Path.of("")).orElseThrow();
        return DevPlugins.scan(root).plugins().stream()
                .filter(p -> p.id().equals("jdbc"))
                .findFirst()
                .orElseThrow();
    }

    /** H2 as a user installs it: a driver plugin with the H2 jar. */
    private InstalledPlugin h2Driver() throws Exception {
        Path jar = Path.of(org.h2.Driver.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path plugin = Files.createDirectories(dir.resolve("h2/lib")).getParent();
        Files.copy(jar, plugin.resolve("lib").resolve(String.valueOf(jar.getFileName())));
        Files.writeString(
                plugin.resolve("plugin.yaml"),
                """
                id: h2
                name: H2
                version: 2.2.224
                api-version: "0.x"
                provides:
                  - { type: driver, identifier: h2, class: org.h2.Driver }
                """);
        return PluginRepository.read(plugin);
    }

    @Test
    void takesAnyDriverAndShipsNone() {
        InstalledPlugin plugin = jdbcPlugin();
        assertThat(plugin.descriptor().anyDrivers()).isTrue();
        assertThat(plugin.classpath())
                .noneMatch(p -> String.valueOf(p.getFileName()).startsWith("h2-"));
    }

    @Test
    void readsWithTheDriverPluginThatAcceptsTheUrl(TestkitEngine engine) throws Exception {
        // a file database: the plugin's copy of H2 does not see the in-memory databases of the tests
        String url = "jdbc:h2:file:" + dir.resolve("db").toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"shop\"");
            statement.execute("CREATE TABLE \"shop\".\"orders\" (\"id\" BIGINT PRIMARY KEY, \"note\" VARCHAR(10))");
            statement.execute("INSERT INTO \"shop\".\"orders\" SELECT X, 'n' || X FROM SYSTEM_RANGE(1, 40)");
        }
        InstalledPlugin plugin = jdbcPlugin();
        try (PluginManager manager = new PluginManager(List.of(plugin, h2Driver()), PluginManager.Options.defaults());
                PluginLease lease = manager.acquire(plugin, "job")) {
            assertThat(Class.forName("org.h2.Driver", false, lease.classLoader())
                            .getClassLoader())
                    .as("the driver comes from the driver plugin, in the plugin's own loader")
                    .isSameAs(lease.classLoader());
            SourceFactory factory = lease.factory(PluginType.SOURCE, "jdbc", SourceFactory.class);
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", url);
            options.put("username", "sa");
            options.put("password", "");
            options.put("table", "shop.orders");
            options.put("split.count", 4);
            String out = Datasets.output();
            try {
                engine.run(TestJob.named("generic")
                                .source(factory, options)
                                .sink(TestkitConnectors.collect(), Map.of("dataset", out))
                                .parallelism(2))
                        .requireSuccess()
                        .requireReconciled();
                List<Row> rows = Datasets.written(out);
                assertThat(rows).hasSize(40);
                assertThat(rows).extracting(r -> r.get(1)).contains("n1", "n40");
            } finally {
                Datasets.release(out);
            }
        }
    }
}
