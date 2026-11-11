/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.devlive.pipeforge.api.expression.OnError;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.NameCase;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.Order;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.Others;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperOptions.Unmapped;
import org.junit.jupiter.api.Test;

class MapperConfigTest {

    static String problem(String option, ThrowingCallable call) {
        PipeForgeException error = catchThrowableOfType(PipeForgeException.class, call);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo(ErrorCode.OPTION_INVALID);
        assertThat(error.params()).containsEntry("option", option).containsEntry("node", "map");
        assertThat(error.scope().node()).isEqualTo("map");
        return String.valueOf(error.params().get("reason"));
    }

    private static String mappingProblem(Map<String, String> entry) {
        return problem("mapping", () -> Fixtures.config(Map.of("mapping", List.of(entry))));
    }

    @Test
    void readsEntriesAndDefaults() {
        MapperConfig config = Fixtures.config(Map.of(
                "mapping",
                List.of(
                        Map.of("from", "id", "to", "order_id"),
                        Map.of(
                                "expr", "amount * 100",
                                "to", "cents",
                                "type", "decimal(14, 2)",
                                "nullable", "false",
                                "default", "0",
                                "comment", "in cents"))));
        assertThat(config.entries()).hasSize(2);
        MappingEntry cents = config.entries().get(1);
        assertThat(cents.type()).isEqualTo(DataTypes.DECIMAL(14, 2));
        assertThat(cents.nullable()).isFalse();
        assertThat(cents.defaultValue()).isEqualTo("0");
        assertThat(cents.comment()).isEqualTo("in cents");
        assertThat(cents.label()).isEqualTo("entry 2 (to: cents)");
        assertThat(config.others()).isEqualTo(Others.KEEP);
        assertThat(config.unmapped()).isEqualTo(Unmapped.FAIL);
        assertThat(config.nameCase()).isEqualTo(NameCase.KEEP);
        assertThat(config.order()).isEqualTo(Order.SOURCE);
        assertThat(config.onError()).isEqualTo(OnError.DIRTY);
    }

    @Test
    void rejectsMalformedEntries() {
        assertThat(mappingProblem(Map.of("form", "id", "to", "x")))
                .isEqualTo("entry 1: unknown key 'form'; did you mean 'from'? (keys: from, to, expr, value, type,"
                        + " nullable, default, comment)");
        assertThat(mappingProblem(Map.of("to", "x"))).isEqualTo("entry 1: give from, expr or value");
        assertThat(mappingProblem(Map.of("value", "1", "expr", "2", "to", "x")))
                .isEqualTo("entry 1: a constant value cannot be combined with from or expr");
        assertThat(mappingProblem(Map.of("expr", "1 + 1")))
                .isEqualTo("entry 1: a computed or constant column needs a name in to");
        assertThat(mappingProblem(Map.of("from", "id", "type", "INTEGER(3")))
                .startsWith("entry 1: 'INTEGER(3' is not a type: ");
        assertThat(mappingProblem(Map.of("from", "id", "nullable", "maybe")))
                .isEqualTo("entry 1: nullable must be true or false");
    }

    @Test
    void rejectsConflictingDrops() {
        assertThat(problem("drop", () -> Fixtures.config(Map.of("drop", List.of("a", "a")))))
                .isEqualTo("column a is listed twice");
        assertThat(problem(
                        "drop",
                        () -> Fixtures.config(
                                Map.of("drop", List.of("ID"), "mapping", List.of(Map.of("from", "id", "to", "x"))))))
                .isEqualTo("column id is both dropped and mapped by entry 1 (to: x)");
    }
}
