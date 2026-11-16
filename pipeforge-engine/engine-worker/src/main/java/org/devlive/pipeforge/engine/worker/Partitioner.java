/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.Partitioning;
import org.jspecify.annotations.Nullable;

/**
 * Chooses the subpartitions of an output a row goes to (native engine design, section 4). The
 * hash of {@code hash} and {@code table} depends only on the values, so every worker and every
 * restart sends a key to the same subtask.
 */
abstract class Partitioner {

    final int subpartitions;

    Partitioner(int subpartitions) {
        this.subpartitions = subpartitions;
    }

    /** Calls {@code target} with every subpartition {@code row} goes to. */
    abstract void route(Row row, IntConsumer target);

    /**
     * The partitioner of an output.
     *
     * @param subtask the subtask of the producer; rebalance starts there to spread the first rows
     * @param tables the tables the output carries, with their columns and primary keys
     */
    static Partitioner of(Partitioning partitioning, int subpartitions, int subtask, List<CatalogTable> tables) {
        return switch (partitioning.kind()) {
            case FORWARD -> new Partitioner(subpartitions) {
                @Override
                void route(Row row, IntConsumer target) {
                    target.accept(0);
                }
            };
            case REBALANCE -> new Rebalance(subpartitions, subtask);
            case HASH -> new Hash(subpartitions, partitioning.keys(), tables);
            case TABLE -> new Partitioner(subpartitions) {
                @Override
                void route(Row row, IntConsumer target) {
                    target.accept(Math.floorMod(mix(table(row.table())), subpartitions));
                }
            };
            case BROADCAST -> new Partitioner(subpartitions) {
                @Override
                void route(Row row, IntConsumer target) {
                    for (int i = 0; i < subpartitions; i++) {
                        target.accept(i);
                    }
                }
            };
        };
    }

    /** Round robin. */
    static final class Rebalance extends Partitioner {
        private int next;

        Rebalance(int subpartitions, int subtask) {
            super(subpartitions);
            this.next = Math.floorMod(subtask, subpartitions);
        }

        @Override
        void route(Row row, IntConsumer target) {
            target.accept(next);
            next = next + 1 == subpartitions ? 0 : next + 1;
        }
    }

    /**
     * By the values of key columns: the named keys, or the table and its primary key when none
     * are named; a table without a primary key is hashed by all its columns.
     */
    static final class Hash extends Partitioner {
        private final List<String> keys;
        private final Map<TableId, CatalogTable> tables = new HashMap<>();
        private final Map<TableId, int[]> positions = new HashMap<>();

        Hash(int subpartitions, List<String> keys, List<CatalogTable> tables) {
            super(subpartitions);
            this.keys = List.copyOf(keys);
            tables.forEach(t -> this.tables.put(t.id(), t));
        }

        @Override
        void route(Row row, IntConsumer target) {
            int[] columns = positions.computeIfAbsent(row.table(), this::positions);
            int hash = keys.isEmpty() ? table(row.table()) : 0;
            for (int column : columns) {
                hash = 31 * hash + value(row.get(column));
            }
            target.accept(Math.floorMod(mix(hash), subpartitions));
        }

        private int[] positions(TableId id) {
            CatalogTable table = tables.get(id);
            if (table == null) {
                throw PipeForgeException.of(ErrorCode.INTERNAL)
                        .param("detail", "a row of table " + id + ", which this output does not carry")
                        .build();
            }
            List<String> names = !keys.isEmpty() ? keys : !table.primaryKey().isEmpty() ? table.primaryKey() : null;
            if (names == null) {
                int[] all = new int[table.columns().size()];
                Arrays.setAll(all, i -> i);
                return all;
            }
            int[] columns = new int[names.size()];
            for (int i = 0; i < columns.length; i++) {
                columns[i] = table.indexOf(names.get(i));
                if (columns[i] < 0) {
                    throw PipeForgeException.of(ErrorCode.INTERNAL)
                            .param("detail", "table " + id + " has no key column " + names.get(i))
                            .build();
                }
            }
            return columns;
        }
    }

    static int table(TableId id) {
        return id.toQualifiedName().hashCode();
    }

    /** A hash of a value that does not depend on the JVM: by content for bytes, by value for decimals. */
    static int value(@Nullable Object value) {
        return switch (value) {
            case null -> 0;
            case byte[] bytes -> Arrays.hashCode(bytes);
            case BigDecimal decimal -> decimal.signum() == 0
                    ? 0
                    : decimal.stripTrailingZeros().hashCode();
            case Row row -> {
                int hash = 1;
                for (int i = 0; i < row.arity(); i++) {
                    hash = 31 * hash + value(row.get(i));
                }
                yield hash;
            }
            case List<?> list -> {
                int hash = 1;
                for (Object element : list) {
                    hash = 31 * hash + value(element);
                }
                yield hash;
            }
            case Map<?, ?> map -> {
                int hash = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    hash += value(entry.getKey()) ^ value(entry.getValue());
                }
                yield hash;
            }
            default -> value.hashCode();
        };
    }

    /** Spreads the bits of a hash (the finalizer of MurmurHash3), so that similar keys do not cluster. */
    static int mix(int hash) {
        int h = hash;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
