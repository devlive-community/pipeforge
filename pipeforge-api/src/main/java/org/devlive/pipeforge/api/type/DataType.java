/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.type;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.annotation.Stable;
import org.jspecify.annotations.Nullable;

/**
 * A logical type: a {@link TypeRoot} with its parameters and nullability. Create types with
 * {@link DataTypes}; {@link #toString()} prints the canonical form that {@link DataTypes#parse}
 * reads back, e.g. {@code DECIMAL(10, 2) NOT NULL} or {@code ROW<id INT64 NOT NULL, tags ARRAY<STRING>>}.
 */
@Stable
public final class DataType {

    /** A named field of a {@code ROW} type. */
    public record Field(String name, DataType type) {
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        @Override
        public String toString() {
            return DataTypes.quoteIfNeeded(name) + " " + type;
        }
    }

    private final TypeRoot root;
    private final boolean nullable;
    private final @Nullable Integer length;
    private final int precision;
    private final int scale;
    private final List<DataType> children;
    private final List<Field> fields;

    DataType(
            TypeRoot root,
            boolean nullable,
            @Nullable Integer length,
            int precision,
            int scale,
            List<DataType> children,
            List<Field> fields) {
        this.root = root;
        this.nullable = nullable;
        this.length = length;
        this.precision = precision;
        this.scale = scale;
        this.children = List.copyOf(children);
        this.fields = List.copyOf(fields);
    }

    public TypeRoot root() {
        return root;
    }

    public boolean isNullable() {
        return nullable;
    }

    /** Maximum length of {@code STRING(n)} or {@code BYTES(n)}; {@code null} means unbounded. */
    public @Nullable Integer length() {
        return length;
    }

    /** Precision of {@code DECIMAL}, or fractional-second digits of {@code TIME} and timestamps. */
    public int precision() {
        return precision;
    }

    /** Scale of {@code DECIMAL}. */
    public int scale() {
        return scale;
    }

    /** Element type of {@code ARRAY}. */
    public DataType elementType() {
        requireRoot(TypeRoot.ARRAY);
        return children.get(0);
    }

    /** Key type of {@code MAP}. */
    public DataType keyType() {
        requireRoot(TypeRoot.MAP);
        return children.get(0);
    }

    /** Value type of {@code MAP}. */
    public DataType valueType() {
        requireRoot(TypeRoot.MAP);
        return children.get(1);
    }

    /** Fields of {@code ROW}; empty for other types. */
    public List<Field> fields() {
        return fields;
    }

    /** The Java class of non-null values. */
    public Class<?> javaClass() {
        return root.javaClass();
    }

    public DataType nullable() {
        return withNullability(true);
    }

    public DataType notNull() {
        return withNullability(false);
    }

    public DataType withNullability(boolean value) {
        return value == nullable ? this : new DataType(root, value, length, precision, scale, children, fields);
    }

    /** Equality that ignores nullability at every level. */
    public boolean equalsIgnoreNullability(DataType other) {
        if (other.root != root
                || !Objects.equals(other.length, length)
                || other.precision != precision
                || other.scale != scale
                || other.children.size() != children.size()
                || other.fields.size() != fields.size()) {
            return false;
        }
        for (int i = 0; i < children.size(); i++) {
            if (!children.get(i).equalsIgnoreNullability(other.children.get(i))) {
                return false;
            }
        }
        for (int i = 0; i < fields.size(); i++) {
            Field mine = fields.get(i);
            Field theirs = other.fields.get(i);
            if (!mine.name().equals(theirs.name()) || !mine.type().equalsIgnoreNullability(theirs.type())) {
                return false;
            }
        }
        return true;
    }

    private void requireRoot(TypeRoot expected) {
        if (root != expected) {
            throw new IllegalStateException(this + " is not " + expected);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DataType type
                && type.root == root
                && type.nullable == nullable
                && Objects.equals(type.length, length)
                && type.precision == precision
                && type.scale == scale
                && type.children.equals(children)
                && type.fields.equals(fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(root, nullable, length, precision, scale, children, fields);
    }

    @Override
    public String toString() {
        String body =
                switch (root) {
                    case DECIMAL -> "DECIMAL(" + precision + ", " + scale + ")";
                    case STRING, BYTES -> length == null ? root.name() : root.name() + "(" + length + ")";
                    case TIME, TIMESTAMP, TIMESTAMP_LTZ -> root.name() + "(" + precision + ")";
                    case ARRAY -> "ARRAY<" + children.get(0) + ">";
                    case MAP -> "MAP<" + children.get(0) + ", " + children.get(1) + ">";
                    case ROW -> "ROW<" + fields.stream().map(Field::toString).collect(Collectors.joining(", ")) + ">";
                    default -> root.name();
                };
        return nullable ? body : body + " NOT NULL";
    }
}
