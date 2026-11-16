/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.engine.rpc.Partitioning;
import org.junit.jupiter.api.Test;

class PartitionerTest {

    static final TableId ORDERS = TableId.of("shop", "orders");
    static final TableId USERS = TableId.of("shop", "users");
    static final List<CatalogTable> TABLES = List.of(
            CatalogTable.of(ORDERS, List.of(Column.of("id", INT64()), Column.of("user", STRING())))
                    .withPrimaryKey("id"),
            CatalogTable.of(USERS, List.of(Column.of("name", STRING()), Column.of("city", STRING()))));

    static List<Integer> route(Partitioner partitioner, Row row) {
        List<Integer> targets = new ArrayList<>();
        partitioner.route(row, targets::add);
        return targets;
    }

    @Test
    void forwardRebalanceAndBroadcast() {
        Row row = GenericRow.of(ORDERS, 1L, "a");
        assertThat(route(Partitioner.of(Partitioning.FORWARD, 1, 3, TABLES), row))
                .containsExactly(0);
        Partitioner rebalance = Partitioner.of(Partitioning.REBALANCE, 3, 1, TABLES);
        List<Integer> spread = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            spread.addAll(route(rebalance, row));
        }
        assertThat(spread).as("round robin from the producer's subtask").containsExactly(1, 2, 0, 1, 2, 0, 1);
        assertThat(route(Partitioner.of(new Partitioning(Partitioning.Kind.BROADCAST, List.of()), 4, 0, TABLES), row))
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    void hashSendsAKeyToOneSubtaskAndSpreadsTheKeys() {
        Partitioner byPrimaryKey = Partitioner.of(Partitioning.hash(List.of()), 4, 0, TABLES);
        Set<Integer> used = new TreeSet<>();
        for (long id = 0; id < 1000; id++) {
            List<Integer> first = route(byPrimaryKey, GenericRow.of(ORDERS, id, "a"));
            assertThat(route(byPrimaryKey, GenericRow.of(ORDERS, id, "other columns do not matter")))
                    .isEqualTo(first);
            used.addAll(first);
        }
        assertThat(used).containsExactly(0, 1, 2, 3);
        // the hash is part of the behaviour of a job: the same key must keep its subtask across versions
        assertThat(List.of(0L, 1L, 2L, 42L).stream()
                        .map(id -> route(byPrimaryKey, GenericRow.of(ORDERS, id, "a"))
                                .get(0))
                        .toList())
                .containsExactly(1, 1, 3, 3);

        Partitioner byUser = Partitioner.of(Partitioning.hash(List.of("user")), 4, 0, TABLES);
        assertThat(route(byUser, GenericRow.of(ORDERS, 1L, "ann")))
                .isEqualTo(route(byUser, GenericRow.of(ORDERS, 2L, "ann")));
        // a table without a primary key is hashed by all its columns
        assertThat(route(byPrimaryKey, GenericRow.of(USERS, "ann", "Berlin")))
                .isEqualTo(route(byPrimaryKey, GenericRow.of(USERS, "ann", "Berlin")));
        assertThatThrownBy(() -> route(byPrimaryKey, GenericRow.of(TableId.of("other"), 1L)))
                .hasMessageContaining("which this output does not carry");
        assertThatThrownBy(() -> route(
                        Partitioner.of(Partitioning.hash(List.of("missing")), 4, 0, TABLES),
                        GenericRow.of(ORDERS, 1L, "a")))
                .hasMessageContaining("has no key column missing");
    }

    @Test
    void tableSendsATableToOneSubtask() {
        Partitioner table = Partitioner.of(new Partitioning(Partitioning.Kind.TABLE, List.of()), 8, 0, TABLES);
        assertThat(route(table, GenericRow.of(ORDERS, 1L, "a")))
                .isEqualTo(route(table, GenericRow.of(ORDERS, 9L, "z")));
        assertThat(route(table, GenericRow.of(USERS, "x", "y"))).hasSize(1);
    }

    @Test
    void valuesHashByContent() {
        assertThat(Partitioner.value(null)).isZero();
        assertThat(Partitioner.value(new byte[] {1, 2})).isEqualTo(Partitioner.value(new byte[] {1, 2}));
        assertThat(Partitioner.value(new BigDecimal("1.50"))).isEqualTo(Partitioner.value(new BigDecimal("1.5")));
        assertThat(Partitioner.value(new BigDecimal("0.00"))).isEqualTo(Partitioner.value(BigDecimal.ZERO));
        assertThat(Partitioner.value(List.of(new byte[] {1}))).isEqualTo(Partitioner.value(List.of(new byte[] {1})));
        assertThat(Partitioner.value(Map.of("k", new byte[] {1})))
                .isEqualTo(Partitioner.value(Map.of("k", new byte[] {1})));
        assertThat(Partitioner.value(GenericRow.of(ORDERS, new byte[] {3})))
                .isEqualTo(Partitioner.value(GenericRow.of(USERS, new byte[] {3})));
        assertThat(Partitioner.value("text")).isEqualTo("text".hashCode());
    }
}
