/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.mysql;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.testkit.SourceContract;
import org.devlive.pipeforge.testkit.SourceUnderTest;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;

/** The testkit's source groups on a MySQL server of one version; a subclass per verified version. */
abstract class MySqlSourceContract extends SourceContract {

    /** MySQL has no arrays, maps or rows. */
    static final Set<TypeRoot> TYPES =
            EnumSet.complementOf(EnumSet.of(TypeRoot.ARRAY, TypeRoot.MAP, TypeRoot.ROW, TypeRoot.NULL));

    private final String version;

    MySqlSourceContract(String version) {
        this.version = version;
    }

    @BeforeAll
    static void docker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    @Override
    protected SourceUnderTest source() {
        MySql mysql = new MySql(version);
        return new SourceUnderTest() {
            @Override
            public SourceFactory factory() {
                return new MySqlSourceFactory();
            }

            @Override
            public Map<String, Object> options(TestTable table) throws Exception {
                mysql.create(table.table(), table.rows());
                return mysql.options(table.table().id());
            }

            @Override
            public void cleanup(TestTable table) throws Exception {
                mysql.drop(table.table().id());
            }

            @Override
            public Set<TypeRoot> supportedTypes() {
                return TYPES;
            }

            @Override
            public boolean holds(DataType type, Object value) {
                return MySql.holds(type, value);
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
