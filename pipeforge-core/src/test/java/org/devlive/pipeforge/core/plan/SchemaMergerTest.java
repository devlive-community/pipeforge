/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchemaMergerTest {

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @CsvSource(
            delimiterString = " | ",
            textBlock =
                    """
            INT32 NOT NULL          | INT32 NOT NULL          | INT32 NOT NULL
            INT32 NOT NULL          | INT32                   | INT32
            INT8                    | INT64 NOT NULL          | INT64
            INT16                   | INT32                   | INT32
            FLOAT32                 | FLOAT64                 | FLOAT64
            INT32                   | DECIMAL(10, 2)          | DECIMAL(12, 2)
            INT64                   | DECIMAL(5, 5)           | DECIMAL(24, 5)
            DECIMAL(10, 2)          | DECIMAL(8, 4)           | DECIMAL(12, 4)
            STRING(10)              | STRING(20)              | STRING(20)
            STRING(10)              | STRING                  | STRING
            STRING                  | STRING(10)              | STRING
            BYTES(4)                | BYTES(2)                | BYTES(4)
            TIMESTAMP(3)            | TIMESTAMP(6)            | TIMESTAMP(6)
            TIMESTAMP_LTZ(6)        | TIMESTAMP_LTZ(3)        | TIMESTAMP_LTZ(6)
            TIME(0)                 | TIME(3)                 | TIME(3)
            """)
    void widensTypes(String a, String b, String expected) {
        assertThat(SchemaMerger.mergeTypes(DataTypes.parse(a), DataTypes.parse(b)))
                .isEqualTo(DataTypes.parse(expected));
        assertThat(SchemaMerger.mergeTypes(DataTypes.parse(b), DataTypes.parse(a)))
                .isEqualTo(DataTypes.parse(expected));
    }

    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource(
            delimiterString = " | ",
            textBlock =
                    """
            INT64             | BYTES             | the types cannot be merged
            INT32             | FLOAT64           | the types cannot be merged
            TIMESTAMP(3)      | TIMESTAMP_LTZ(3)  | TIMESTAMP and TIMESTAMP_LTZ mean different things
            DECIMAL(38, 30)   | INT64             | would need 49 digits
            """)
    void rejectsIncompatibleTypes(String a, String b, String reason) {
        assertThatThrownBy(() -> SchemaMerger.mergeTypes(DataTypes.parse(a), DataTypes.parse(b)))
                .hasMessageContaining(reason);
    }

    @Test
    void tablesWithDifferentIdsStaySeparateAndKeysSurviveWhenTheyAgree() {
        TableId users = TableId.of("users");
        CatalogTable a = CatalogTable.of(
                        users, List.of(Column.of("id", DataTypes.INT32().notNull())))
                .withPrimaryKey("id");
        CatalogTable b = CatalogTable.of(
                        users, List.of(Column.of("id", DataTypes.INT64().notNull())))
                .withPrimaryKey("id");
        CatalogTable other = CatalogTable.of(TableId.of("other"), List.of(Column.of("x", DataTypes.STRING())));
        Map<String, List<CatalogTable>> inputs = new LinkedHashMap<>();
        inputs.put("left", List.of(a, other));
        inputs.put("right", List.of(b));
        inputs.put("same", List.of(a));
        List<CatalogTable> merged = SchemaMerger.merge("n", inputs);
        assertThat(merged).extracting(CatalogTable::id).containsExactly(users, TableId.of("other"));
        assertThat(merged.get(0).column("id").orElseThrow().type())
                .isEqualTo(DataTypes.INT64().notNull());
        assertThat(merged.get(0).primaryKey()).containsExactly("id");
        assertThat(merged.get(1)).isEqualTo(other);
    }
}
