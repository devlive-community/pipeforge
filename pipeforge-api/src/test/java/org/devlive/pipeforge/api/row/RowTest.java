/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.row;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RowTest {

    private static final TableId ORDERS = TableId.of("shop", "orders");

    @Test
    void genericRowsHoldValuesAndKind() {
        GenericRow row = GenericRow.of(ORDERS, 1L, "book", null);
        assertThat(row.kind()).isEqualTo(RowKind.INSERT);
        assertThat(row.table()).isEqualTo(ORDERS);
        assertThat(row.arity()).isEqualTo(3);
        assertThat(row.get(1)).isEqualTo("book");
        assertThat(row.isNullAt(2)).isTrue();
        assertThat(row.isNullAt(0)).isFalse();
        row.set(2, 9.5);
        assertThat(row.get(2)).isEqualTo(9.5);
        row.setKind(RowKind.UPDATE_AFTER);
        assertThat(row.kind()).isEqualTo(RowKind.UPDATE_AFTER);
    }

    @Test
    void withKindCopiesTheValues() {
        GenericRow row = GenericRow.of(ORDERS, 1L);
        GenericRow deleted = row.withKind(RowKind.DELETE);
        row.set(0, 2L);
        assertThat(deleted.kind()).isEqualTo(RowKind.DELETE);
        assertThat(deleted.get(0)).isEqualTo(1L);
    }

    @Test
    void comparesByKindTableAndDeepValues() {
        GenericRow a = GenericRow.ofKind(ORDERS, RowKind.INSERT, new byte[] {1, 2}, "x");
        GenericRow b = GenericRow.copyOf(GenericRow.of(ORDERS, new byte[] {1, 2}, "x"));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(a.withKind(RowKind.DELETE));
        assertThat(a).isNotEqualTo(GenericRow.of(TableId.of("other"), new byte[] {1, 2}, "x"));
        assertThat(a).isNotEqualTo("row");
        assertThat(GenericRow.of(ORDERS, 1, "a")).hasToString("+Ishop.orders(1, a)");
        assertThat(new GenericRow(ORDERS, RowKind.DELETE, 2).toString()).isEqualTo("-Dshop.orders(null, null)");
    }

    @Test
    void rowKindsKnowTheirShortNames() {
        assertThat(RowKind.UPDATE_BEFORE.shortName()).isEqualTo("-U");
        assertThat(RowKind.INSERT.isAccumulate()).isTrue();
        assertThat(RowKind.UPDATE_AFTER.isAccumulate()).isTrue();
        assertThat(RowKind.DELETE.isAccumulate()).isFalse();
    }

    @Test
    void tableIdsParseAndPrintDottedNames() {
        assertThat(TableId.parse("t")).isEqualTo(TableId.of("t"));
        assertThat(TableId.parse("db.t")).isEqualTo(TableId.of("db", "t"));
        assertThat(TableId.parse("db.s.t")).isEqualTo(new TableId(null, "db", "s", "t"));
        assertThat(TableId.parse("c.db.s.t").catalog()).isEqualTo("c");
        assertThat(new TableId("c", null, "s", "t")).hasToString("c.s.t");
        assertThatThrownBy(() -> TableId.parse("a.b.c.d.e")).hasMessageContaining("Too many parts");
        assertThatThrownBy(() -> TableId.of("")).hasMessageContaining("must not be empty");
    }
}
