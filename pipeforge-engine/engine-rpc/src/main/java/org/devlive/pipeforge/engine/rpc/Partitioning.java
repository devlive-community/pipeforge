/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.List;
import java.util.Objects;

/**
 * How the rows of an edge are distributed over the parallel instances of its target.
 *
 * @param kind the strategy
 * @param keys the key columns of {@link Kind#HASH}; empty means by table and its primary key
 */
public record Partitioning(Kind kind, List<String> keys) {

    /** The strategies (native engine design, section 4). */
    public enum Kind {
        /** Instance i to instance i, within one thread; needs the same parallelism on both sides. */
        FORWARD,
        /** Round robin, when the parallelism changes. */
        REBALANCE,
        /** By key, so that the changes of one key reach one instance in order. */
        HASH,
        /** By table, so that a table always reaches the same instance. */
        TABLE,
        /** Every row to every instance. */
        BROADCAST
    }

    public static final Partitioning FORWARD = new Partitioning(Kind.FORWARD, List.of());
    public static final Partitioning REBALANCE = new Partitioning(Kind.REBALANCE, List.of());

    public Partitioning {
        Objects.requireNonNull(kind, "kind");
        keys = List.copyOf(keys);
        if (kind != Kind.HASH && !keys.isEmpty()) {
            throw new IllegalArgumentException("Only HASH partitioning has keys, not " + kind);
        }
    }

    public static Partitioning hash(List<String> keys) {
        return new Partitioning(Kind.HASH, keys);
    }

    /** Whether every instance of the source may send to every instance of the target. */
    public boolean allToAll() {
        return kind != Kind.FORWARD;
    }

    @Override
    public String toString() {
        return kind == Kind.HASH
                ? "hash(" + (keys.isEmpty() ? "table, primary key" : String.join(", ", keys)) + ")"
                : kind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
