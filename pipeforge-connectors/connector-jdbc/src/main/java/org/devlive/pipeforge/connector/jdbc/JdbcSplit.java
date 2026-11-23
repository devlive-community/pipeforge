/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.source.SourceSplit;
import org.jspecify.annotations.Nullable;

/**
 * A part of a table to read: a range of the split column, its NULLs, or everything.
 *
 * @param splitId unique within the source
 * @param table the table the split reads, or {@code null} for a query
 * @param kind which part
 * @param column the split column of {@link Kind#RANGE} and {@link Kind#NULLS}; {@code null} for {@link
 *     Kind#ALL}
 * @param lower with {@link Kind#RANGE}: the smallest value, inclusive; {@code null} for no bound
 * @param upper with {@link Kind#RANGE}: the bound, exclusive; {@code null} for no bound
 * @param watermark with {@link Kind#RANGE}: the split column's value up to which every row was
 *     read; a resumed split reads only the rows above it, in the order of the column. {@code null}
 *     before the first row
 */
public record JdbcSplit(
        String splitId,
        @Nullable TableId table,
        Kind kind,
        @Nullable String column,
        @Nullable Long lower,
        @Nullable Long upper,
        @Nullable Long watermark)
        implements SourceSplit {

    /** A split that starts at its beginning. */
    public JdbcSplit(
            String splitId,
            @Nullable TableId table,
            Kind kind,
            @Nullable String column,
            @Nullable Long lower,
            @Nullable Long upper) {
        this(splitId, table, kind, column, lower, upper, null);
    }

    /**
     * Whether the split is read in the order of its column, so that it can resume after a
     * watermark: ranges of a table; whole tables, queries and NULLs resume from their start.
     */
    public boolean ordered() {
        return kind == Kind.RANGE && column != null && table != null;
    }

    /** The same split, read up to {@code value} of its column. */
    public JdbcSplit resumedAfter(long value) {
        if (!ordered()) {
            throw new IllegalStateException("Split " + splitId + " is not read in order");
        }
        return new JdbcSplit(splitId, table, kind, column, lower, upper, value);
    }

    /** {@code ORDER BY} for an ordered split, or an empty string. */
    public String orderBy(JdbcDialect dialect) {
        return ordered() ? " ORDER BY " + dialect.quote(java.util.Objects.requireNonNull(column)) : "";
    }

    public enum Kind {
        /** The whole table, or the query. */
        ALL,
        /** Rows whose split column is in a range. */
        RANGE,
        /** Rows whose split column is NULL. */
        NULLS
    }

    /** All of {@code table}, or of the query when it is {@code null}. */
    public static JdbcSplit all(@Nullable TableId table) {
        return new JdbcSplit(
                table == null ? "all" : table.toQualifiedName() + "/all", table, Kind.ALL, null, null, null);
    }

    /** The WHERE clause on the split column, with {@code ?} for the bounds; {@code null} for none. */
    public @Nullable String predicate(JdbcDialect dialect) {
        if (kind == Kind.ALL || column == null) {
            return null;
        }
        String quoted = dialect.quote(column);
        if (kind == Kind.NULLS) {
            return quoted + " IS NULL";
        }
        String range;
        if (lower == null && upper == null) {
            range = quoted + " IS NOT NULL";
        } else if (lower == null) {
            range = quoted + " < ?";
        } else {
            range = upper == null ? quoted + " >= ?" : quoted + " >= ? AND " + quoted + " < ?";
        }
        return watermark == null ? range : range + " AND " + quoted + " > ?";
    }

    /** The values of the {@code ?} of {@link #predicate}, in order. */
    public List<Long> parameters() {
        List<Long> values = new ArrayList<>(2);
        if (kind == Kind.RANGE) {
            if (lower != null) {
                values.add(lower);
            }
            if (upper != null) {
                values.add(upper);
            }
            if (watermark != null) {
                values.add(watermark);
            }
        }
        return values;
    }

    /** Writes splits; version 2 adds the table, version 3 the watermark. */
    public static final class Serializer implements VersionedSerializer<JdbcSplit> {
        public static final Serializer INSTANCE = new Serializer();

        @Override
        public int version() {
            return 3;
        }

        @Override
        public byte[] serialize(JdbcSplit split) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                write(out, split);
            }
            return bytes.toByteArray();
        }

        @Override
        public JdbcSplit deserialize(int version, byte[] bytes) throws IOException {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                return read(in, version);
            }
        }

        static void write(DataOutputStream out, JdbcSplit split) throws IOException {
            out.writeUTF(split.splitId());
            TableId table = split.table();
            out.writeBoolean(table != null);
            if (table != null) {
                writeName(out, table.catalog());
                writeName(out, table.database());
                writeName(out, table.schema());
                out.writeUTF(table.table());
            }
            out.writeUTF(split.kind().name());
            out.writeBoolean(split.column() != null);
            if (split.column() != null) {
                out.writeUTF(split.column());
            }
            writeBound(out, split.lower());
            writeBound(out, split.upper());
            writeBound(out, split.watermark());
        }

        static JdbcSplit read(DataInputStream in, int version) throws IOException {
            String id = in.readUTF();
            TableId table = null;
            if (version >= 2 && in.readBoolean()) {
                table = new TableId(readName(in), readName(in), readName(in), in.readUTF());
            }
            Kind kind = Kind.valueOf(in.readUTF());
            String column = in.readBoolean() ? in.readUTF() : null;
            Long lower = readBound(in);
            Long upper = readBound(in);
            Long watermark = version >= 3 ? readBound(in) : null;
            return new JdbcSplit(id, table, kind, column, lower, upper, watermark);
        }

        private static void writeName(DataOutputStream out, @Nullable String name) throws IOException {
            out.writeBoolean(name != null);
            if (name != null) {
                out.writeUTF(name);
            }
        }

        private static @Nullable String readName(DataInputStream in) throws IOException {
            return in.readBoolean() ? in.readUTF() : null;
        }

        private static void writeBound(DataOutputStream out, @Nullable Long bound) throws IOException {
            out.writeBoolean(bound != null);
            if (bound != null) {
                out.writeLong(bound);
            }
        }

        private static @Nullable Long readBound(DataInputStream in) throws IOException {
            return in.readBoolean() ? in.readLong() : null;
        }
    }

    /** Writes the remaining splits of an enumerator; version 2 adds their tables, version 3 watermarks. */
    public static final class ListSerializer implements VersionedSerializer<List<JdbcSplit>> {
        public static final ListSerializer INSTANCE = new ListSerializer();

        @Override
        public int version() {
            return 3;
        }

        @Override
        public byte[] serialize(List<JdbcSplit> splits) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(splits.size());
                for (JdbcSplit split : splits) {
                    Serializer.write(out, split);
                }
            }
            return bytes.toByteArray();
        }

        @Override
        public List<JdbcSplit> deserialize(int version, byte[] bytes) throws IOException {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int count = in.readInt();
                List<JdbcSplit> splits = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    splits.add(Serializer.read(in, version));
                }
                return splits;
            }
        }
    }
}
