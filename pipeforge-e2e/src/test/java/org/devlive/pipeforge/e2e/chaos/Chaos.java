/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the plan of a chaos scenario against a cluster on a thread of its own while the scenario
 * works, records every fault, and keeps everything a failed run leaves to be looked at: the logs of
 * the processes, the metrics the scenario collected, the checkpoints and the record of the
 * injections, under {@code target/chaos/<scenario>-<seed>/}.
 */
public final class Chaos implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Chaos.class);

    /** Waits; tests that check the timing of a plan without waiting for it pass their own. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final String scenario;
    private final ChaosPlan plan;
    private final Faults faults;
    private final ChaosRecord record;
    private final Path archive;
    private final Sleeper sleeper;
    private final Thread thread;
    private volatile @Nullable Injection active;
    private volatile @Nullable Throwable broken;
    private volatile long started;

    /**
     * @param root where runs keep what they leave, e.g. {@code target/chaos}
     */
    public Chaos(String scenario, ChaosPlan plan, Faults faults, Path root) {
        this(scenario, plan, faults, root, Thread::sleep);
    }

    public Chaos(String scenario, ChaosPlan plan, Faults faults, Path root, Sleeper sleeper) {
        this.scenario = scenario;
        this.plan = plan;
        this.faults = faults;
        this.sleeper = sleeper;
        this.archive = root.resolve(scenario + "-" + plan.seed());
        this.record = new ChaosRecord(archive.resolve("injections.jsonl"), scenario, plan);
        this.thread = new Thread(this::run, "chaos-" + scenario);
        thread.setDaemon(true);
        LOG.info(
                "Chaos scenario {} with seed {} ({} faults); replay it with -Dpipeforge.chaos.seed={}",
                scenario,
                plan.seed(),
                plan.injections().size(),
                plan.seed());
    }

    /** Starts injecting, as the plan says, from now. */
    public Chaos start() {
        started = System.nanoTime();
        thread.start();
        return this;
    }

    private long now() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private void run() {
        long clock = 0;
        try {
            for (Injection injection : plan.injections()) {
                sleeper.sleep(Math.max(0, injection.atMs() - clock));
                clock = injection.atMs();
                active = injection;
                record.add(new ChaosRecord.Event("inject", now(), injection, attempt(() -> faults.inject(injection))));
                sleeper.sleep(injection.durationMs());
                clock += injection.durationMs();
                heal(injection);
            }
        } catch (InterruptedException e) {
            Injection left = active;
            if (left != null) {
                heal(left);
            }
        } catch (RuntimeException | Error e) {
            broken = e;
        }
    }

    private void heal(Injection injection) {
        record.add(new ChaosRecord.Event("heal", now(), injection, attempt(() -> faults.heal(injection))));
        active = null;
    }

    private interface Step {
        void run() throws Exception;
    }

    private static @Nullable String attempt(Step step) {
        try {
            step.run();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Waits until every fault of the plan was injected and healed. */
    public void awaitEnd(Duration timeout) throws InterruptedException {
        thread.join(timeout.toMillis());
        if (thread.isAlive()) {
            throw new AssertionError("the chaos plan did not end within " + timeout);
        }
        Throwable failure = broken;
        if (failure != null) {
            throw new AssertionError("the chaos plan broke", failure);
        }
    }

    /** Stops injecting and heals the fault that is on. */
    @Override
    public void close() throws InterruptedException {
        thread.interrupt();
        thread.join(TimeUnit.MINUTES.toMillis(2));
    }

    public ChaosRecord record() {
        return record;
    }

    public ChaosPlan plan() {
        return plan;
    }

    /** Where this run keeps what it leaves. */
    public Path archive() {
        return archive;
    }

    /**
     * Keeps what a failed run leaves next to the record of its injections: the logs under {@code
     * logs}, the checkpoints, and the metrics the scenario collected, by file name. Returns the
     * archive; the record is there already.
     */
    public Path keep(@Nullable Path logs, @Nullable Path checkpoints, Map<String, String> metrics) throws IOException {
        if (logs != null) {
            copy(logs, archive.resolve("logs"));
        }
        if (checkpoints != null) {
            copy(checkpoints, archive.resolve("checkpoints"));
        }
        for (Map.Entry<String, String> entry : metrics.entrySet()) {
            Path file = archive.resolve("metrics").resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
        LOG.error(
                "Chaos scenario {} failed with seed {}; what it left is in {}; replay it with"
                        + " -Dpipeforge.chaos.seed={}",
                scenario,
                plan.seed(),
                archive,
                plan.seed());
        return archive;
    }

    private static void copy(Path from, Path to) throws IOException {
        if (!Files.exists(from)) {
            return;
        }
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
