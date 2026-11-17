/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.postgres;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.testkit.SinkContract;
import org.devlive.pipeforge.testkit.SinkUnderTest;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;

/** The testkit's sink groups on a PostgreSQL server of one version; a subclass per verified version. */
abstract class PostgresSinkContract extends SinkContract {

    /** PostgreSQL has no maps and no rows; one-byte integers go into smallint. */
    static final Set<TypeRoot> TYPES =
            java.util.EnumSet.complementOf(java.util.EnumSet.of(TypeRoot.MAP, TypeRoot.ROW, TypeRoot.NULL));

    private final String version;

    PostgresSinkContract(String version) {
        this.version = version;
    }

    @BeforeAll
    static void docker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    @Override
    protected SinkUnderTest sink() {
        Postgres postgres = new Postgres(version);
        return new SinkUnderTest() {
            @Override
            public SinkFactory factory() {
                return new PostgresSinkFactory();
            }

            @Override
            public Map<String, Object> options(TestTable table) throws Exception {
                postgres.create(table.table(), List.of());
                return postgres.options(table.table().id());
            }

            @Override
            public Optional<List<Row>> readBack(TestTable table, Map<String, Object> options) throws Exception {
                return Optional.of(postgres.rows(table.table()));
            }

            @Override
            public void cleanup(TestTable table, Map<String, Object> options) throws Exception {
                postgres.drop(table.table().id());
            }

            @Override
            public Set<TypeRoot> supportedTypes() {
                return TYPES;
            }

            @Override
            public int largeRowCount() {
                return 20_000;
            }
        };
    }
}
