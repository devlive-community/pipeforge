/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.postgres;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.testkit.SourceContract;
import org.devlive.pipeforge.testkit.SourceUnderTest;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;

/** The testkit's source groups on a PostgreSQL server of one version; a subclass per verified version. */
abstract class PostgresSourceContract extends SourceContract {

    /** PostgreSQL has no one-byte integer (a smallint reads as INT16), no maps and no rows. */
    static final Set<TypeRoot> TYPES =
            EnumSet.complementOf(EnumSet.of(TypeRoot.INT8, TypeRoot.MAP, TypeRoot.ROW, TypeRoot.NULL));

    private final String version;

    PostgresSourceContract(String version) {
        this.version = version;
    }

    @BeforeAll
    static void docker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    @Override
    protected SourceUnderTest source() {
        Postgres postgres = new Postgres(version);
        return new SourceUnderTest() {
            @Override
            public SourceFactory factory() {
                return new PostgresSourceFactory();
            }

            @Override
            public Map<String, Object> options(TestTable table) throws Exception {
                postgres.create(table.table(), table.rows());
                return postgres.options(table.table().id());
            }

            @Override
            public void cleanup(TestTable table) throws Exception {
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

            @Override
            public Map<String, Map<String, Object>> splitStrategies() {
                return Map.of(
                        "one split", Map.of("split.mode", "none"),
                        "ranges of the primary key", Map.of("split.count", 16));
            }
        };
    }
}
