/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A job document as read from YAML or JSON, with the location of every value. */
sealed interface Tree permits Tree.Scalar, Tree.Mapping, Tree.Sequence {

    Location location();

    /** The value as plain Java objects: maps, lists, strings, numbers, booleans and null. */
    @Nullable
    Object toPlain();

    /** A text, number, boolean or null. */
    record Scalar(@Nullable Object value, Location location) implements Tree {
        @Override
        public @Nullable Object toPlain() {
            return value;
        }
    }

    /** A mapping whose keys are texts, in document order. */
    record Mapping(Map<String, Tree> entries, Location location) implements Tree {
        public Mapping {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }

        @Override
        public Map<String, Object> toPlain() {
            Map<String, Object> plain = new LinkedHashMap<>();
            entries.forEach((key, value) -> plain.put(key, value.toPlain()));
            return plain;
        }
    }

    /** A list of values. */
    record Sequence(List<Tree> items, Location location) implements Tree {
        public Sequence {
            items = List.copyOf(items);
        }

        @Override
        public List<Object> toPlain() {
            List<Object> plain = new ArrayList<>(items.size());
            items.forEach(item -> plain.add(item.toPlain()));
            return plain;
        }
    }
}
