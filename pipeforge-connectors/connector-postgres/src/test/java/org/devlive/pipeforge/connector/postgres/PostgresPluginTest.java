/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import org.devlive.pipeforge.common.plugin.PluginType;
import org.devlive.pipeforge.plugin.DevPlugins;
import org.devlive.pipeforge.plugin.InstalledPlugin;
import org.devlive.pipeforge.plugin.PluginLease;
import org.devlive.pipeforge.plugin.PluginManager;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.TestkitEngine;
import org.devlive.pipeforge.testkit.TestkitExtension;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.RowAssertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.DockerClientFactory;

/** The plugin as a server loads it: from this module, with the PostgreSQL JDBC driver in its own class path. */
@ExtendWith(TestkitExtension.class)
class PostgresPluginTest {

    private static InstalledPlugin postgresPlugin() {
        Path root = DevPlugins.findRoot(Path.of("")).orElseThrow();
        return DevPlugins.scan(root).plugins().stream()
                .filter(p -> p.id().equals("postgres"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shipsTheDriverAndNeedsNoDriverPlugin() {
        InstalledPlugin plugin = postgresPlugin();
        assertThat(plugin.descriptor().drivers()).isEmpty();
        assertThat(plugin.classpath())
                .anyMatch(p -> String.valueOf(p.getFileName()).startsWith("postgresql-42."));
    }

    @Test
    void readsWithTheDriverFromItsOwnClassPath(TestkitEngine engine) throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        Postgres postgres = new Postgres("16");
        TableId id = TableId.of("shop", "plugin_rows");
        CatalogTable table = CatalogTable.of(
                        id,
                        List.of(
                                Column.of("id", DataTypes.INT64().notNull()),
                                Column.of("amount", DataTypes.DECIMAL(8, 2))))
                .withPrimaryKey("id");
        List<Row> rows = new ArrayList<>();
        for (long i = 0; i < 50; i++) {
            rows.add(GenericRow.of(id, i, BigDecimal.valueOf(i, 1).setScale(2)));
        }
        postgres.create(table, rows);

        InstalledPlugin plugin = postgresPlugin();
        try (PluginManager manager = new PluginManager(List.of(plugin), PluginManager.Options.defaults());
                PluginLease lease = manager.acquire(plugin, "job")) {
            assertThat(Class.forName("org.postgresql.Driver", false, lease.classLoader())
                            .getClassLoader())
                    .as("the driver comes from the plugin's own loader")
                    .isSameAs(lease.classLoader());
            SourceFactory factory = lease.factory(PluginType.SOURCE, "jdbc-postgres", SourceFactory.class);
            String out = Datasets.output();
            try {
                engine.run(TestJob.named("plugin")
                                .source(factory, postgres.options(id))
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
