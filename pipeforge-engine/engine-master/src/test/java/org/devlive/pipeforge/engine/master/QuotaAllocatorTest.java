/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuotaAllocatorTest {

    private record Case(
            String name,
            List<Integer> active,
            Map<Integer, Double> used,
            Set<Integer> throttled,
            Map<Integer, Double> expected) {}

    @Test
    void splitsTheLimitAfterWhatEachReaderUsed() {
        List<Case> cases = List.of(
                new Case(
                        "nothing known: equal shares",
                        List.of(0, 1, 2, 3),
                        Map.of(),
                        Set.of(),
                        Map.of(0, 250.0, 1, 250.0, 2, 250.0, 3, 250.0)),
                new Case(
                        "an idle reader keeps the floor, the busy ones share the rest",
                        List.of(0, 1, 2, 3),
                        Map.of(0, 0.0, 1, 250.0, 2, 250.0, 3, 250.0),
                        Set.of(1, 2, 3),
                        Map.of(0, 25.0, 1, 325.0, 2, 325.0, 3, 325.0)),
                new Case(
                        "a reader that did not wait gets its use and a quarter more",
                        List.of(0, 1),
                        Map.of(0, 100.0, 1, 500.0),
                        Set.of(1),
                        Map.of(0, 125.0, 1, 875.0)),
                new Case(
                        "nobody waits: the rest is shared by everyone",
                        List.of(0, 1),
                        Map.of(0, 100.0, 1, 100.0),
                        Set.of(),
                        Map.of(0, 500.0, 1, 500.0)),
                new Case(
                        "readers that use more than the limit are scaled back to it",
                        List.of(0, 1),
                        Map.of(0, 800.0, 1, 800.0),
                        Set.of(),
                        Map.of(0, 500.0, 1, 500.0)),
                new Case(
                        "a new reader starts with an equal share",
                        List.of(0, 1, 2),
                        Map.of(0, 100.0, 1, 100.0),
                        Set.of(1),
                        Map.of(0, 125.0, 1, 541.666, 2, 333.333)),
                new Case("one reader gets everything", List.of(5), Map.of(5, 10.0), Set.of(), Map.of(5, 1000.0)),
                new Case("no readers", List.of(), Map.of(), Set.of(), Map.of()));
        for (Case c : cases) {
            Map<Integer, Double> shares = QuotaAllocator.split(1000, c.active(), c.used(), c.throttled());
            assertThat(shares.keySet()).as(c.name()).containsExactlyElementsOf(c.active());
            c.expected().forEach((reader, rate) -> assertThat(shares.get(reader))
                    .as(c.name() + ", reader " + reader)
                    .isCloseTo(rate, within(0.01)));
            if (!c.active().isEmpty()) {
                assertThat(shares.values().stream()
                                .mapToDouble(Double::doubleValue)
                                .sum())
                        .as(c.name() + ": the shares add up to the limit")
                        .isCloseTo(1000, within(1e-6));
            }
        }
    }

    @Test
    void measuresUseBetweenTwoPeriods() {
        QuotaAllocator.Usage usage = new QuotaAllocator.Usage();
        Set<Integer> throttled = new HashSet<>();
        assertThat(usage.since(Map.of(0, new long[] {0, 0}, 1, new long[] {0, 0}), 0, throttled))
                .as("the first period has nothing to compare with")
                .isEmpty();
        Map<Integer, Double> used = usage.since(
                Map.of(0, new long[] {500, 50_000_000}, 1, new long[] {100, 200_000_000}), 1_000_000_000L, throttled);
        assertThat(used).containsEntry(0, 500.0).containsEntry(1, 100.0);
        assertThat(throttled).as("a tenth of the period or more").containsExactly(1);
    }
}
