/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * Merges the tables a node receives from several inputs. Tables with different ids are kept side
 * by side; tables with the same id are merged column by column:
 *
 * <ul>
 *   <li>columns missing from some inputs are added and become nullable;
 *   <li>integers widen ({@code INT32} + {@code INT64} is {@code INT64}), {@code FLOAT32} widens to
 *       {@code FLOAT64}, integers and decimals become a decimal that holds both;
 *   <li>{@code DECIMAL(p1,s1)} + {@code DECIMAL(p2,s2)} is {@code DECIMAL(max(p1-s1,p2-s2) +
 *       max(s1,s2), max(s1,s2))};
 *   <li>strings and bytes take the longer length, times and timestamps the larger precision;
 *   <li>anything else, including {@code TIMESTAMP} with {@code TIMESTAMP_LTZ}, is an error.
 * </ul>
 *
 * <p>The primary key and partition keys are kept when every input agrees on them.
 */
final class SchemaMerger {

    private SchemaMerger() {}

    /**
     * @param node the receiving node, for errors
     * @param inputs upstream node id to the tables it produces, in input order
     * @throws PipeForgeException {@link ErrorCode#SCHEMA_INCOMPATIBLE} when two schemas of a table cannot be merged
     */
    static List<CatalogTable> merge(String node, Map<String, List<CatalogTable>> inputs) {
        Map<TableId, List<Map.Entry<String, CatalogTable>>> byTable = new LinkedHashMap<>();
        inputs.forEach((upstream, tables) -> {
            for (CatalogTable table : tables) {
                byTable.computeIfAbsent(table.id(), id -> new ArrayList<>()).add(Map.entry(upstream, table));
            }
        });
        List<CatalogTable> merged = new ArrayList<>();
        for (List<Map.Entry<String, CatalogTable>> versions : byTable.values()) {
            CatalogTable result = versions.get(0).getValue();
            List<String> upstreams = new ArrayList<>(List.of(versions.get(0).getKey()));
            for (Map.Entry<String, CatalogTable> next : versions.subList(1, versions.size())) {
                result = mergeTables(node, String.join(", ", upstreams), result, next.getKey(), next.getValue());
                upstreams.add(next.getKey());
            }
            merged.add(result);
        }
        return merged;
    }

    private static CatalogTable mergeTables(
            String node, String leftUpstream, CatalogTable left, String rightUpstream, CatalogTable right) {
        if (left.equals(right)) {
            return left;
        }
        Map<String, Column> columns = new LinkedHashMap<>();
        for (Column column : left.columns()) {
            Column other = right.column(column.name()).orElse(null);
            if (other == null) {
                columns.put(column.name(), column.withType(column.type().nullable()));
                continue;
            }
            DataType type;
            try {
                type = mergeTypes(column.type(), other.type());
            } catch (IllegalArgumentException e) {
                throw PipeForgeException.of(ErrorCode.SCHEMA_INCOMPATIBLE)
                        .param("node", node)
                        .param("upstream", leftUpstream + ", " + rightUpstream)
                        .param(
                                "reason",
                                "column '" + column.name() + "' of table " + left.id() + " is "
                                        + column.type() + " in '" + leftUpstream + "' and " + other.type() + " in '"
                                        + rightUpstream + "': " + e.getMessage())
                        .build();
            }
            columns.put(column.name(), column.withType(type));
        }
        for (Column column : right.columns()) {
            columns.putIfAbsent(column.name(), column.withType(column.type().nullable()));
        }
        List<String> primaryKey = left.primaryKey().equals(right.primaryKey()) ? left.primaryKey() : List.of();
        List<String> partitions = left.partitionKeys().equals(right.partitionKeys()) ? left.partitionKeys() : List.of();
        return new CatalogTable(
                left.id(),
                List.copyOf(columns.values()),
                primaryKey,
                partitions,
                left.options(),
                left.comment() != null ? left.comment() : right.comment());
    }

    /** The type that holds the values of both, or {@link IllegalArgumentException} with the reason. */
    static DataType mergeTypes(DataType a, DataType b) {
        boolean nullable = a.isNullable() || b.isNullable();
        if (a.equalsIgnoreNullability(b)) {
            return a.withNullability(nullable);
        }
        TypeRoot x = a.root();
        TypeRoot y = b.root();
        DataType merged;
        if (x.isIntegral() && y.isIntegral()) {
            merged = integerDigits(a) >= integerDigits(b) ? a : b;
        } else if (isFloat(x) && isFloat(y)) {
            merged = DataTypes.FLOAT64();
        } else if ((x == TypeRoot.DECIMAL || x.isIntegral()) && (y == TypeRoot.DECIMAL || y.isIntegral())) {
            int scale = Math.max(scale(a), scale(b));
            int digits = Math.max(integerDigits(a), integerDigits(b)) + scale;
            if (digits > DataTypes.MAX_DECIMAL_PRECISION) {
                throw new IllegalArgumentException("the merged decimal would need " + digits + " digits, more than "
                        + DataTypes.MAX_DECIMAL_PRECISION);
            }
            merged = DataTypes.DECIMAL(digits, scale);
        } else if (x == y && (x == TypeRoot.STRING || x == TypeRoot.BYTES)) {
            merged = longer(a, b);
        } else if (x == y && (x == TypeRoot.TIME || x == TypeRoot.TIMESTAMP || x == TypeRoot.TIMESTAMP_LTZ)) {
            merged = a.precision() >= b.precision() ? a : b;
        } else if ((x == TypeRoot.TIMESTAMP && y == TypeRoot.TIMESTAMP_LTZ)
                || (x == TypeRoot.TIMESTAMP_LTZ && y == TypeRoot.TIMESTAMP)) {
            throw new IllegalArgumentException(
                    "TIMESTAMP and TIMESTAMP_LTZ mean different things; convert one of them explicitly");
        } else {
            throw new IllegalArgumentException("the types cannot be merged");
        }
        return merged.withNullability(nullable);
    }

    private static boolean isFloat(TypeRoot root) {
        return root == TypeRoot.FLOAT32 || root == TypeRoot.FLOAT64;
    }

    private static int scale(DataType type) {
        return type.root() == TypeRoot.DECIMAL ? type.scale() : 0;
    }

    private static int integerDigits(DataType type) {
        return switch (type.root()) {
            case INT8 -> 3;
            case INT16 -> 5;
            case INT32 -> 10;
            case INT64 -> 19;
            default -> type.precision() - type.scale();
        };
    }

    private static DataType longer(DataType a, DataType b) {
        @Nullable Integer x = a.length();
        @Nullable Integer y = b.length();
        if (x == null) {
            return a;
        }
        if (y == null) {
            return b;
        }
        return x >= y ? a : b;
    }
}
