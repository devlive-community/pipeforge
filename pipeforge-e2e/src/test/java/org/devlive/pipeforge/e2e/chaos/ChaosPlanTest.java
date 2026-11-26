/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plans come from their seed alone. */
class ChaosPlanTest {

    static final List<Target> TARGETS = List.of(
            Target.process("worker-1"),
            Target.process("master"),
            Target.link("worker-1->master"),
            Target.storage("checkpoints"));

    @Test
    void theSameSeedGivesTheSamePlanAndAnotherSeedAnother() {
        ChaosPlan first = ChaosPlan.of(42, ChaosPlan.NIGHTLY, TARGETS);
        ChaosPlan again = ChaosPlan.of(42, ChaosPlan.NIGHTLY, TARGETS);
        ChaosPlan other = ChaosPlan.of(43, ChaosPlan.NIGHTLY, TARGETS);
        assertThat(again.injections()).isEqualTo(first.injections());
        assertThat(other.injections()).isNotEqualTo(first.injections());
        // 20 minutes with a fault every 1 to 3 minutes, each 10 to 60 seconds
        assertThat(first.injections()).hasSizeBetween(5, 20);
    }

    @Test
    void faultsKeepTheirGapsLengthsAndTargets() {
        ChaosPlan.Settings settings = new ChaosPlan.Settings(
                Duration.ofMinutes(30),
                Duration.ofSeconds(60),
                Duration.ofSeconds(180),
                Duration.ofSeconds(10),
                Duration.ofSeconds(60));
        for (long seed = 1; seed <= 50; seed++) {
            ChaosPlan plan = ChaosPlan.of(seed, settings, TARGETS);
            long end = 0;
            for (Injection injection : plan.injections()) {
                long gap = injection.atMs() - end;
                assertThat(gap).as("gap before %s", injection.describe()).isBetween(60_000L, 180_000L);
                assertThat(injection.durationMs()).isBetween(10_000L, 60_000L);
                assertThat(injection.atMs()).isLessThan(30 * 60_000L);
                Target target = TARGETS.stream()
                        .filter(t -> t.name().equals(injection.target()))
                        .findFirst()
                        .orElseThrow();
                assertThat(target.faults()).contains(injection.fault());
                switch (injection.fault()) {
                    case DELAY -> assertThat(injection.param()).isBetween(50L, 1000L);
                    case LOSS -> assertThat(injection.param()).isBetween(5L, 30L);
                    case STORAGE_SLOW -> assertThat(injection.param()).isBetween(500L, 5000L);
                    default -> assertThat(injection.param()).isZero();
                }
                end = injection.atMs() + injection.durationMs();
            }
            assertThat(plan.injections())
                    .extracting(Injection::number)
                    .isSorted()
                    .startsWith(1);
        }
    }

    @Test
    void refusesWhatCannotBePlanned() {
        assertThatThrownBy(() -> new Target("w", Target.Kind.PROCESS, EnumSet.of(Fault.PARTITION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Target("w", Target.Kind.PROCESS, EnumSet.noneOf(Fault.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChaosPlan.of(1, ChaosPlan.NIGHTLY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChaosPlan.Settings(
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        System.setProperty("pipeforge.chaos.seed", "1234");
        try {
            assertThat(ChaosPlan.runSeed()).isEqualTo(1234);
        } finally {
            System.clearProperty("pipeforge.chaos.seed");
        }
    }
}
