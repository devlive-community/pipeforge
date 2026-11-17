/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JdbcSplitterTest {

    private static final GenericDialect DIALECT = new GenericDialect();
    private static final org.devlive.pipeforge.api.row.TableId TABLE =
            org.devlive.pipeforge.api.row.TableId.parse("shop.orders");

    @Test
    void cutsTheValuesIntoRangesOpenAtBothEnds() {
        List<JdbcSplit> splits = JdbcSplitter.ranges(TABLE, "id", 0L, 99L, 4, false);
        assertThat(splits)
                .extracting(JdbcSplit::lower, JdbcSplit::upper)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(null, 25L),
                        org.assertj.core.groups.Tuple.tuple(25L, 50L),
                        org.assertj.core.groups.Tuple.tuple(50L, 75L),
                        org.assertj.core.groups.Tuple.tuple(75L, null));
        assertThat(splits).extracting(JdbcSplit::splitId).doesNotHaveDuplicates();
        assertThat(splits.get(0).predicate(DIALECT)).isEqualTo("\"id\" < ?");
        assertThat(splits.get(1).predicate(DIALECT)).isEqualTo("\"id\" >= ? AND \"id\" < ?");
        assertThat(splits.get(1).parameters()).containsExactly(25L, 50L);
        assertThat(splits.get(3).predicate(DIALECT)).isEqualTo("\"id\" >= ?");
    }

    @Test
    void handlesFewValuesTheWholeLongRangeEmptyAndNullableColumns() {
        assertThat(JdbcSplitter.ranges(TABLE, "id", 5L, 7L, 16, false))
                .as("no more ranges than values")
                .hasSize(3);
        assertThat(JdbcSplitter.ranges(TABLE, "id", 5L, 5L, 16, false))
                .singleElement()
                .satisfies(s -> assertThat(s.predicate(DIALECT)).isEqualTo("\"id\" IS NOT NULL"));
        List<JdbcSplit> all = JdbcSplitter.ranges(TABLE, "id", Long.MIN_VALUE, Long.MAX_VALUE, 3, false);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).upper()).isNotNull();
        assertThat(all.get(2).lower()).isNotNull();

        List<JdbcSplit> empty = JdbcSplitter.ranges(TABLE, "id", null, null, 8, true);
        assertThat(empty).extracting(JdbcSplit::kind).containsExactly(JdbcSplit.Kind.RANGE, JdbcSplit.Kind.NULLS);
        assertThat(empty.get(1).predicate(DIALECT)).isEqualTo("\"id\" IS NULL");
        assertThat(empty.get(1).parameters()).isEmpty();
        assertThat(JdbcSplit.all(TABLE).predicate(DIALECT)).isNull();
    }

    @Test
    void splitsOfTheFirstVersionHaveNoTable() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeUTF("range-1");
            out.writeUTF("RANGE");
            out.writeBoolean(true);
            out.writeUTF("id");
            out.writeBoolean(true);
            out.writeLong(25);
            out.writeBoolean(false);
        }
        assertThat(JdbcSplit.Serializer.INSTANCE.deserialize(1, bytes.toByteArray()))
                .isEqualTo(new JdbcSplit("range-1", null, JdbcSplit.Kind.RANGE, "id", 25L, null));
    }

    @Test
    void splitsAndTheirListsSerialize() throws Exception {
        List<JdbcSplit> splits = JdbcSplitter.ranges(TABLE, "order id", -10L, 10L, 3, true);
        for (JdbcSplit split : splits) {
            JdbcSplit.Serializer serializer = JdbcSplit.Serializer.INSTANCE;
            assertThat(serializer.deserialize(serializer.version(), serializer.serialize(split)))
                    .isEqualTo(split);
        }
        JdbcSplit.ListSerializer list = JdbcSplit.ListSerializer.INSTANCE;
        assertThat(list.deserialize(list.version(), list.serialize(splits))).isEqualTo(splits);
        assertThat(JdbcSplit.Serializer.INSTANCE.deserialize(
                        2, JdbcSplit.Serializer.INSTANCE.serialize(JdbcSplit.all(null))))
                .isEqualTo(JdbcSplit.all(null));
        assertThat(splits).allSatisfy(s -> assertThat(s.splitId()).startsWith("shop.orders/"));
    }
}
