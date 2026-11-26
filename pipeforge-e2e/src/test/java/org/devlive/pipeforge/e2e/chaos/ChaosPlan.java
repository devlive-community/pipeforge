/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * The faults of a run, all chosen from its seed before it starts: the same seed, settings and
 * targets give the same plan, so a run that failed is replayed by its seed (test strategy, section
 * 3.2). Faults follow each other with a random gap; one heals before the next starts, so that every
 * fault is seen on its own.
 *
 * @param length how long the faults go on; the last one starts before its end
 * @param minGap the shortest time from the end of one fault to the start of the next
 * @param maxGap the longest
 * @param minFault the shortest a fault lasts
 * @param maxFault the longest
 */
public record ChaosPlan(
        long seed,
        Duration length,
        Duration minGap,
        Duration maxGap,
        Duration minFault,
        Duration maxFault,
        List<Target> targets,
        List<Injection> injections) {

    /** The gaps and lengths of the nightly scenarios: a fault every 1 to 3 minutes for 20 minutes. */
    public static final Settings NIGHTLY = new Settings(
            Duration.ofMinutes(20),
            Duration.ofMinutes(1),
            Duration.ofMinutes(3),
            Duration.ofSeconds(10),
            Duration.ofSeconds(60));

    /** How long a run lasts, the gaps between its faults and how long each lasts. */
    public record Settings(Duration length, Duration minGap, Duration maxGap, Duration minFault, Duration maxFault) {
        public Settings {
            if (minGap.isNegative()
                    || maxGap.compareTo(minGap) < 0
                    || minFault.isNegative()
                    || maxFault.compareTo(minFault) < 0
                    || length.isNegative()) {
                throw new IllegalArgumentException("gaps and faults need 0 <= min <= max, and a length");
            }
        }
    }

    public ChaosPlan {
        targets = List.copyOf(targets);
        injections = List.copyOf(injections);
    }

    /** The plan of {@code seed} over {@code targets}. */
    public static ChaosPlan of(long seed, Settings settings, List<Target> targets) {
        Objects.requireNonNull(settings, "settings");
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("a plan needs targets");
        }
        SplittableRandom random = new SplittableRandom(seed);
        List<Injection> injections = new ArrayList<>();
        long at = between(random, settings.minGap(), settings.maxGap());
        while (at < settings.length().toMillis()) {
            Target target = targets.get(random.nextInt(targets.size()));
            List<Fault> faults = target.faults().stream().sorted().toList();
            Fault fault = faults.get(random.nextInt(faults.size()));
            long duration = between(random, settings.minFault(), settings.maxFault());
            long param =
                    switch (fault) {
                        case DELAY -> 50 + random.nextInt(951); // 50 ms to 1 s
                        case LOSS -> 5 + random.nextInt(26); // 5 to 30 percent
                        case STORAGE_SLOW -> 500 + random.nextInt(4501); // 0.5 to 5 s
                        default -> 0;
                    };
            injections.add(new Injection(injections.size() + 1, at, fault, target.name(), duration, param));
            at += duration + between(random, settings.minGap(), settings.maxGap());
        }
        return new ChaosPlan(
                seed,
                settings.length(),
                settings.minGap(),
                settings.maxGap(),
                settings.minFault(),
                settings.maxFault(),
                targets,
                injections);
    }

    private static long between(SplittableRandom random, Duration min, Duration max) {
        long low = min.toMillis();
        long high = max.toMillis();
        return high == low ? low : low + random.nextLong(high - low + 1);
    }

    /** The seed of a run: {@code -Dpipeforge.chaos.seed} to replay one, else a new one. */
    public static long runSeed() {
        String given = System.getProperty("pipeforge.chaos.seed", "").strip();
        return given.isEmpty() ? new SplittableRandom().nextLong() : Long.parseLong(given);
    }
}
