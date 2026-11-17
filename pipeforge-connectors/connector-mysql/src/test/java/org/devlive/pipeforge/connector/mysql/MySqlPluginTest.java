/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
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
import org.devlive.pipeforge.testkit.data.RowAssertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

/**
 * The plugin as a server loads it: from this module, without Connector/J, which only a driver
 * plugin brings.
 */
@ExtendWith(TestkitExtension.class)
class MySqlPluginTest {

    @TempDir
    Path dir;

    /** This module as a development plugin. */
    private static InstalledPlugin mysqlPlugin() {
        Path root = DevPlugins.findRoot(Path.of("")).orElseThrow();
        return DevPlugins.scan(root).plugins().stream()
                .filter(p -> p.id().equals("mysql"))
                .findFirst()
                .orElseThrow();
    }

    /** The mysql-connector-j driver plugin, from the Connector/J jar of the tests. */
    private InstalledPlugin driverPlugin() throws Exception {
        Path jar = Path.of(com.mysql.cj.jdbc.Driver.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path plugin =
                Files.createDirectories(dir.resolve("mysql-connector-j/lib")).getParent();
        Files.copy(jar, plugin.resolve("lib").resolve(String.valueOf(jar.getFileName())));
        Files.writeString(
                plugin.resolve("plugin.yaml"),
                """
                id: mysql-connector-j
                name: MySQL Connector/J
                version: 8.4.0
                api-version: "0.x"
                provides:
                  - { type: driver, identifier: mysql-connector-j, class: com.mysql.cj.jdbc.Driver }
                """);
        return PluginRepository.read(plugin);
    }

    @Test
    void theDescriptorRequiresTheDriverPlugin() {
        InstalledPlugin plugin = mysqlPlugin();
        assertThat(plugin.descriptor().drivers()).containsExactly("mysql-connector-j");
        assertThat(plugin.classpath()).as("Connector/J is not packaged").noneMatch(p -> p.toString()
                .contains("mysql-connector-j"));
        try (PluginManager manager = new PluginManager(List.of(plugin), PluginManager.Options.defaults())) {
            assertThatThrownBy(() -> manager.acquire(plugin, "job"))
                    .isInstanceOfSatisfying(
                            PipeForgeException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.DRIVER_MISSING));
        }
    }

    @Test
    void readsWithConnectorJFromTheDriverPlugin(TestkitEngine engine) throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        MySql mysql = new MySql("8.4");
        TableId id = TableId.of("shop", "plugin_rows");
        CatalogTable table = CatalogTable.of(
                        id,
                        List.of(
                                Column.of("id", DataTypes.INT64().notNull()),
                                Column.of("amount", DataTypes.DECIMAL(8, 2))))
                .withPrimaryKey("id");
        List<Row> rows = new ArrayList<>();
        for (long i = 0; i < 50; i++) {
            rows.add(GenericRow.of(id, i, BigDecimal.valueOf(i, 1)));
        }
        mysql.create(table, rows);

        InstalledPlugin plugin = mysqlPlugin();
        try (PluginManager manager =
                        new PluginManager(List.of(plugin, driverPlugin()), PluginManager.Options.defaults());
                PluginLease lease = manager.acquire(plugin, "job")) {
            assertThat(Class.forName("com.mysql.cj.jdbc.Driver", false, lease.classLoader())
                            .getClassLoader())
                    .as("Connector/J comes from the driver plugin, in the plugin's own loader")
                    .isSameAs(lease.classLoader());
            SourceFactory factory = lease.factory(PluginType.SOURCE, "jdbc-mysql", SourceFactory.class);
            String out = Datasets.output();
            try {
                engine.run(TestJob.named("plugin")
                                .source(factory, mysql.options(id))
                                .sink(TestkitConnectors.collect(), Map.of("dataset", out))
                                .parallelism(2))
                        .requireSuccess()
                        .requireReconciled();
                RowAssertions.assertSameRows("rows", rows, Datasets.written(out));
            } finally {
                Datasets.release(out);
            }
        }
    }
}
