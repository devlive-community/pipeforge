/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.mysql;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.testkit.SinkContract;
import org.devlive.pipeforge.testkit.SinkUnderTest;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;

/** The testkit's sink groups on a MySQL server of one version; a subclass per verified version. */
abstract class MySqlSinkContract extends SinkContract {

    private final String version;

    MySqlSinkContract(String version) {
        this.version = version;
    }

    @BeforeAll
    static void docker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    @Override
    protected SinkUnderTest sink() {
        MySql mysql = new MySql(version);
        return new SinkUnderTest() {
            @Override
            public SinkFactory factory() {
                return new MySqlSinkFactory();
            }

            @Override
            public Map<String, Object> options(TestTable table) throws Exception {
                mysql.create(table.table(), List.of());
                return mysql.options(table.table().id());
            }

            @Override
            public Optional<List<Row>> readBack(TestTable table, Map<String, Object> options) throws Exception {
                return Optional.of(mysql.rows(table.table()));
            }

            @Override
            public void cleanup(TestTable table, Map<String, Object> options) throws Exception {
                mysql.drop(table.table().id());
            }

            @Override
            public Set<TypeRoot> supportedTypes() {
                return MySqlSourceContract.TYPES;
            }

            @Override
            public boolean holds(DataType type, Object value) {
                return MySql.holds(type, value);
            }

            @Override
            public int largeRowCount() {
                return 20_000;
            }
        };
    }
}
