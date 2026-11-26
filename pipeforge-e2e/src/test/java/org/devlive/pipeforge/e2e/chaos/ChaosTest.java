/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runner injects and heals every fault of its plan, in order, and records it. */
class ChaosTest {

    @TempDir
    Path dir;

    /** Faults that only remember what they were asked, and fail on one target. */
    static final class Remembered implements Faults {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void inject(Injection injection) throws Exception {
            calls.add("inject " + injection.number());
            if (injection.target().equals("broken")) {
                throw new IllegalStateException("no such process");
            }
        }

        @Override
        public void heal(Injection injection) {
            calls.add("heal " + injection.number());
        }
    }

    static ChaosPlan plan(long seed) {
        return ChaosPlan.of(
                seed,
                ChaosPlan.NIGHTLY,
                List.of(Target.process("worker-1"), Target.link("worker-1->master"), Target.process("broken")));
    }

    @Test
    void injectsAndHealsEveryFaultInOrderAndRecordsIt() throws Exception {
        Remembered faults = new Remembered();
        List<Long> sleeps = new CopyOnWriteArrayList<>();
        ChaosPlan plan = plan(99);
        Chaos chaos = new Chaos("unit", plan, faults, dir, sleeps::add).start();
        chaos.awaitEnd(Duration.ofSeconds(10));

        int count = plan.injections().size();
        assertThat(faults.calls).hasSize(2 * count);
        for (int i = 0; i < count; i++) {
            assertThat(faults.calls.get(2 * i)).isEqualTo("inject " + (i + 1));
            assertThat(faults.calls.get(2 * i + 1)).isEqualTo("heal " + (i + 1));
        }
        // the waits follow the plan: the gap before each fault, then its length
        long clock = 0;
        for (int i = 0; i < count; i++) {
            Injection injection = plan.injections().get(i);
            assertThat(sleeps.get(2 * i)).isEqualTo(injection.atMs() - clock);
            assertThat(sleeps.get(2 * i + 1)).isEqualTo(injection.durationMs());
            clock = injection.atMs() + injection.durationMs();
        }

        List<ChaosRecord.Event> events = chaos.record().events();
        assertThat(events).hasSize(2 * count);
        assertThat(events)
                .filteredOn(e ->
                        e.injection().target().equals("broken") && e.event().equals("inject"))
                .allSatisfy(e -> assertThat(e.error()).isEqualTo("IllegalStateException: no such process"));
        assertThat(events)
                .filteredOn(e -> !e.injection().target().equals("broken"))
                .allSatisfy(e -> assertThat(e.error()).isNull());

        List<String> lines = Files.readAllLines(dir.resolve("unit-99/injections.jsonl"));
        assertThat(lines).hasSize(1 + 2 * count);
        JsonNode head = ChaosRecord.JSON.readTree(lines.get(0));
        assertThat(head.path("seed").asLong()).isEqualTo(99);
        assertThat(head.path("replay").asText()).isEqualTo("-Dpipeforge.chaos.seed=99");
        assertThat(head.path("plan").size()).isEqualTo(count);
        assertThat(ChaosRecord.JSON.readTree(lines.get(1)).path("event").asText())
                .isEqualTo("inject");
    }

    @Test
    void stoppingHealsTheFaultThatIsOn() throws Exception {
        Remembered faults = new Remembered();
        ChaosPlan plan = ChaosPlan.of(
                5,
                new ChaosPlan.Settings(
                        Duration.ofSeconds(30),
                        Duration.ofMillis(10),
                        Duration.ofMillis(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30)),
                List.of(Target.process("worker-1")));
        Chaos chaos = new Chaos("stopped", plan, faults, dir).start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (faults.calls.isEmpty()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
        chaos.close();
        assertThat(faults.calls).containsExactly("inject 1", "heal 1");
        assertThat(chaos.record().events()).extracting(ChaosRecord.Event::event).containsExactly("inject", "heal");
    }

    @Test
    void aFailedRunKeepsItsLogsCheckpointsMetricsAndRecord() throws Exception {
        Path logs = Files.createDirectories(dir.resolve("cluster/logs"));
        Files.writeString(logs.resolve("worker-1.out"), "Task failed\n");
        Path checkpoints = Files.createDirectories(dir.resolve("cluster/checkpoints/run-1/chk-3"));
        Files.writeString(checkpoints.resolve("_metadata"), "meta");
        Chaos chaos = new Chaos("kept", plan(7), new Remembered(), dir.resolve("chaos"), millis -> {});
        chaos.start().awaitEnd(Duration.ofSeconds(10));

        Path archive = chaos.keep(logs, dir.resolve("cluster/checkpoints"), Map.of("run-1.json", "{\"rowsRead\":5}"));
        assertThat(archive).isEqualTo(dir.resolve("chaos/kept-7"));
        assertThat(archive.resolve("logs/worker-1.out")).hasContent("Task failed");
        assertThat(archive.resolve("checkpoints/run-1/chk-3/_metadata")).hasContent("meta");
        assertThat(archive.resolve("metrics/run-1.json")).hasContent("{\"rowsRead\":5}");
        assertThat(archive.resolve("injections.jsonl")).exists();
        assertThat(chaos.archive()).isEqualTo(archive);
        assertThat(chaos.plan().seed()).isEqualTo(7);
        // what a run did not have is not there
        chaos.keep(null, dir.resolve("missing"), Map.of());
    }
}
