/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.NameCase;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void writesNamesInEveryStyle() {
        assertThat(Names.apply(NameCase.KEEP, "createdAt")).isEqualTo("createdAt");
        assertThat(Names.apply(NameCase.LOWER, "CreatedAt")).isEqualTo("createdat");
        assertThat(Names.apply(NameCase.UPPER, "created_at")).isEqualTo("CREATED_AT");
        assertThat(Names.apply(NameCase.SNAKE, "createdAt")).isEqualTo("created_at");
        assertThat(Names.apply(NameCase.SNAKE, "HTTPServerURL2")).isEqualTo("http_server_url2");
        assertThat(Names.apply(NameCase.SNAKE, "Order ID")).isEqualTo("order_id");
        assertThat(Names.apply(NameCase.SNAKE, "already_snake")).isEqualTo("already_snake");
        assertThat(Names.apply(NameCase.CAMEL, "created_at")).isEqualTo("createdAt");
        assertThat(Names.apply(NameCase.CAMEL, "ORDER_ID")).isEqualTo("orderId");
        assertThat(Names.apply(NameCase.CAMEL, "user-name.first")).isEqualTo("userNameFirst");
        assertThat(Names.apply(NameCase.CAMEL, "_")).isEqualTo("_");
    }

    @Test
    void findsColumnsAndSuggestsCloseNames() {
        List<String> columns = List.of("id", "Name", "name2");
        assertThat(Names.find(columns, "id")).isZero();
        assertThat(Names.find(columns, "NAME")).isEqualTo(1);
        assertThat(Names.find(List.of("a", "A"), "a")).isZero();
        assertThat(Names.find(List.of("a", "A"), "b")).isEqualTo(-1);
        assertThat(Names.find(List.of("ab", "AB"), "Ab")).isEqualTo(-1);
        assertThat(Names.suggestion("nmae", columns)).isEqualTo("; did you mean 'Name'?");
        assertThat(Names.suggestion("zzzz", columns)).isEmpty();
    }

    @Test
    void quotesIdentifiersAndLiterals() {
        assertThat(Names.quote("a`b")).isEqualTo("`a``b`");
        assertThat(Names.literal("it's")).isEqualTo("'it''s'");
    }

    @Test
    void writesTypesTheWayCastReadsThem() {
        for (String type : List.of(
                "INT64",
                "DECIMAL(10, 2)",
                "STRING",
                "STRING(10)",
                "BYTES(4)",
                "TIMESTAMP(3)",
                "TIMESTAMP_LTZ(3)",
                "ARRAY<STRING(4)>",
                "MAP<STRING, INT64>",
                "ROW<a INT32, b STRING>",
                "DATE",
                "BOOLEAN",
                "JSON")) {
            DataType parsed = DataTypes.parse(type);
            String cast = "CAST(NULL AS " + Names.castType(parsed) + ")";
            assertThat(Fixtures.EXPRESSIONS
                            .analyze(List.of(cast), List.of())
                            .get(0)
                            .type())
                    .as(cast)
                    .isEqualTo(parsed.nullable());
        }
    }
}
