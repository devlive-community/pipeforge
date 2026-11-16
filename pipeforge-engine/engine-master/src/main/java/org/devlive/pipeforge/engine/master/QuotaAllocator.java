/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits the rate limit of a job among its running readers, once per period, after what each used
 * in the last one:
 *
 * <ul>
 *   <li>a reader that did not wait for its quota gets what it used with a quarter more, at least a
 *       tenth of an equal share, so that an idle reader holds little but can start again;
 *   <li>a reader whose use is not known yet, such as a new one, gets an equal share;
 *   <li>readers that waited for their quota share the rest equally; without any, everyone shares it;
 *   <li>the shares always add up to the limit.
 * </ul>
 */
final class QuotaAllocator {

    /** How much more than its use a reader that did not wait gets. */
    static final double HEADROOM = 1.25;

    /** The least a running reader gets, as a share of an equal share. */
    static final double FLOOR = 0.1;

    private QuotaAllocator() {}

    /**
     * The rate of each reader.
     *
     * @param total the rate of the job, e.g. rows per second
     * @param active the running readers
     * @param used what each reader used per second in the last period; missing when not known
     * @param throttled the readers that waited for their quota in the last period
     */
    static Map<Integer, Double> split(
            double total, List<Integer> active, Map<Integer, Double> used, Set<Integer> throttled) {
        Map<Integer, Double> shares = new LinkedHashMap<>();
        if (active.isEmpty() || total <= 0) {
            return shares;
        }
        double equal = total / active.size();
        double floor = equal * FLOOR;
        double given = 0;
        List<Integer> waiting = active.stream().filter(throttled::contains).toList();
        for (int reader : active) {
            if (throttled.contains(reader)) {
                continue;
            }
            Double use = used.get(reader);
            double share = use == null ? equal : Math.max(floor, use * HEADROOM);
            shares.put(reader, share);
            given += share;
        }
        double rest = total - given;
        if (!waiting.isEmpty()) {
            double each = Math.max(floor, rest / waiting.size());
            waiting.forEach(reader -> shares.put(reader, each));
        } else if (rest > 0) {
            double each = rest / active.size();
            shares.replaceAll((reader, share) -> share + each);
        }
        double sum = shares.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<Integer, Double> scaled = new LinkedHashMap<>();
        for (int reader : active) {
            scaled.put(reader, shares.get(reader) * total / sum);
        }
        return scaled;
    }

    /** What the readers did in the last period, from the cumulative counters of their reports. */
    static final class Usage {
        private final Map<Integer, long[]> previous = new HashMap<>();
        private long previousAt;

        /**
         * Records the counters and returns the rows per second since the last call, and in {@code
         * throttled} the readers that waited for their quota a tenth of the period or more.
         *
         * @param counters per reader: rows read so far and nanoseconds waited for the quota so far
         */
        Map<Integer, Double> since(Map<Integer, long[]> counters, long now, Set<Integer> throttled) {
            Map<Integer, Double> used = new HashMap<>();
            double seconds = (now - previousAt) / 1e9;
            for (Map.Entry<Integer, long[]> entry : counters.entrySet()) {
                long[] before = previous.get(entry.getKey());
                long[] after = entry.getValue();
                if (before != null && seconds > 0) {
                    used.put(entry.getKey(), (after[0] - before[0]) / seconds);
                    if (after[1] - before[1] >= (now - previousAt) / 10) {
                        throttled.add(entry.getKey());
                    }
                }
            }
            previous.clear();
            previous.putAll(counters);
            previousAt = now;
            return used;
        }
    }
}
