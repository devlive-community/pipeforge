/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.devlive.pipeforge.api.type.DataTypes.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DataTypesTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "int|INT32",
                "INTEGER|INT32",
                "bigint|INT64",
                "smallint|INT16",
                "tinyint|INT8",
                "float|FLOAT32",
                "double|FLOAT64",
                "bool|BOOLEAN",
                "decimal|DECIMAL(10, 0)",
                "numeric(12)|DECIMAL(12, 0)",
                "DECIMAL( 10 ,2 )|DECIMAL(10, 2)",
                "varchar(255)|STRING(255)",
                "text|STRING",
                "char(1)|STRING(1)",
                "varbinary(16)|BYTES(16)",
                "date|DATE",
                "time|TIME(0)",
                "time(3)|TIME(3)",
                "timestamp|TIMESTAMP(6)",
                "datetime(3)|TIMESTAMP(3)",
                "timestamp_ltz(9)|TIMESTAMP_LTZ(9)",
                "json|JSON",
                "int not null|INT32 NOT NULL",
                "INT NULL|INT32",
                "array<int not null>|ARRAY<INT32 NOT NULL>",
                "map<string, array<bigint>>|MAP<STRING, ARRAY<INT64>>",
                "row<id bigint not null, `first name` string>|ROW<id INT64 NOT NULL, `first name` STRING>",
                "row<>|ROW<>",
            })
    void parsesAliasesIntoCanonicalTypes(String text, String canonical) {
        assertThat(parse(text)).hasToString(canonical);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "BOOLEAN",
                "INT64 NOT NULL",
                "DECIMAL(38, 38)",
                "STRING(10) NOT NULL",
                "BYTES",
                "TIMESTAMP_LTZ(3)",
                "ARRAY<MAP<STRING, ROW<a INT32, b ARRAY<DATE NOT NULL>>>> NOT NULL",
                "NULL",
                "ROW<`a``b` INT8>",
            })
    void canonicalFormsRoundTrip(String canonical) {
        DataType type = parse(canonical);
        assertThat(type).hasToString(canonical);
        assertThat(parse(type.toString())).isEqualTo(type).hasSameHashCodeAs(type);
    }

    @Test
    void comparesTypesByStructureAndNullability() {
        assertThat(DECIMAL(10, 2)).isEqualTo(DECIMAL(10, 2)).isNotEqualTo(DECIMAL(10, 3));
        assertThat(STRING(5)).isNotEqualTo(STRING());
        assertThat(INT32().notNull()).isNotEqualTo(INT32());
        assertThat(INT32().notNull().nullable()).isEqualTo(INT32());
        assertThat(ARRAY(INT32().notNull())
                        .equalsIgnoreNullability(ARRAY(INT32()).notNull()))
                .isTrue();
        assertThat(ROW(FIELD("a", INT32())).equalsIgnoreNullability(ROW(FIELD("b", INT32()))))
                .isFalse();
        assertThat(MAP(STRING(), INT32()).equalsIgnoreNullability(MAP(STRING(), INT64())))
                .isFalse();
        assertThat(INT32().equalsIgnoreNullability(INT64())).isFalse();
        assertThat(INT32()).isNotEqualTo("INT32");
    }

    @Test
    void exposesParametersAndJavaClasses() {
        DataType decimal = DECIMAL(12, 4);
        assertThat(decimal.root()).isEqualTo(TypeRoot.DECIMAL);
        assertThat(decimal.precision()).isEqualTo(12);
        assertThat(decimal.scale()).isEqualTo(4);
        assertThat(decimal.javaClass()).isEqualTo(BigDecimal.class);
        assertThat(STRING(8).length()).isEqualTo(8);
        assertThat(STRING().length()).isNull();
        assertThat(TIMESTAMP_LTZ().javaClass()).isEqualTo(Instant.class);
        assertThat(ARRAY(DATE()).elementType()).isEqualTo(DATE());
        DataType map = MAP(STRING(), INT64());
        assertThat(map.keyType()).isEqualTo(STRING());
        assertThat(map.valueType()).isEqualTo(INT64());
        assertThat(ROW(FIELD("x", BOOLEAN())).fields()).containsExactly(FIELD("x", BOOLEAN()));
        assertThatThrownBy(() -> INT32().elementType()).isInstanceOf(IllegalStateException.class);
        assertThat(INT32().isNullable()).isTrue();
        assertThat(INT32().notNull().isNullable()).isFalse();
        assertThat(INT32().withNullability(true)).isEqualTo(INT32());
    }

    @Test
    void classifiesTypeRoots() {
        assertThat(TypeRoot.DECIMAL.isNumeric()).isTrue();
        assertThat(TypeRoot.STRING.isNumeric()).isFalse();
        assertThat(TypeRoot.INT16.isIntegral()).isTrue();
        assertThat(TypeRoot.FLOAT64.isIntegral()).isFalse();
        assertThat(TypeRoot.TIMESTAMP_LTZ.isTemporal()).isTrue();
        assertThat(TypeRoot.JSON.isTemporal()).isFalse();
        assertThat(TypeRoot.ARRAY.javaClass()).isEqualTo(List.class);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "varchr|position 1: unknown type 'varchr'",
                "decimal(39, 2)|DECIMAL precision must be between 1 and 38",
                "decimal(5, 6)|DECIMAL scale must be between 0 and the precision",
                "array<int|position 10: expected '>'",
                "map<int>|expected ','",
                "int not|expected NULL after NOT",
                "int extra|unexpected 'extra'",
                "string(0)|STRING length must be positive",
                "timestamp(10)|precision must be between 0 and 9",
                "row<a int, a string>|Duplicate ROW field: a",
                "row<`a int>|unterminated quoted field name",
                "decimal(x)|expected a number",
                "|unexpected end of type",
                "string(99999999999)|number too large",
            })
    void reportsErrorsWithPositions(String text, String message) {
        assertThatThrownBy(() -> parse(text == null ? "" : text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }
}
