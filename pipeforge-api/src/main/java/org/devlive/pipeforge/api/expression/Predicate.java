/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.expression;

import java.util.List;
import java.util.Objects;
import org.devlive.pipeforge.api.annotation.Evolving;
import org.jspecify.annotations.Nullable;

/**
 * A filter condition handed to sources that support filter push-down. Only simple comparisons
 * of a column with literals are pushed down; anything else stays in the job.
 */
@Evolving
public sealed interface Predicate {

    /** Comparison operators. */
    enum Operator {
        EQ("="),
        NE("<>"),
        LT("<"),
        LE("<="),
        GT(">"),
        GE(">=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /** {@code column <op> value}. */
    record Comparison(String column, Operator operator, Object value) implements Predicate {
        public Comparison {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(value, "value");
        }
    }

    /** {@code column IS [NOT] NULL}. */
    record IsNull(String column, boolean negated) implements Predicate {}

    /** {@code column IN (values)}; values may contain {@code null}. */
    record In(String column, List<@Nullable Object> values) implements Predicate {
        public In {
            values = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
        }
    }

    record And(List<Predicate> children) implements Predicate {
        public And {
            children = List.copyOf(children);
        }
    }

    record Or(List<Predicate> children) implements Predicate {
        public Or {
            children = List.copyOf(children);
        }
    }

    record Not(Predicate child) implements Predicate {}

    /** The columns the predicate reads, in order of first use. */
    default List<String> columns() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        collectColumns(this, out);
        return List.copyOf(out);
    }

    private static void collectColumns(Predicate predicate, java.util.Set<String> out) {
        switch (predicate) {
            case Comparison c -> out.add(c.column());
            case IsNull n -> out.add(n.column());
            case In in -> out.add(in.column());
            case And and -> and.children().forEach(child -> collectColumns(child, out));
            case Or or -> or.children().forEach(child -> collectColumns(child, out));
            case Not not -> collectColumns(not.child(), out);
        }
    }
}
