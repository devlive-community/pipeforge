/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.core.dirty.DirtySample;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The dirty data policy from end to end: ten rows, ids 0 to 9, through numbers → upper → collect. */
class DirtyDataTest {

    private static final String SKIP = "dirty: { policy: skip }";

    /**
     * One case of the policy table.
     *
     * @param error the code the job fails with, or {@code null} when it succeeds
     * @param dirty bad rows per node when it succeeds
     */
    private record Case(
            String name,
            String source,
            String transform,
            String sink,
            String env,
            @Nullable String error,
            @Nullable String errorNode,
            long written,
            Map<String, Long> dirty) {}

    private LocalEngine engine;

    @BeforeEach
    void start() {
        engine = new LocalEngine(1);
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    @Test
    void appliesThePolicyAtEveryNodeAndReconciles() throws Exception {
        List<Case> cases = List.of(
                new Case(
                        "fail: a transform refuses a row", "", "bad-ids: 3", "", "", "PF-DATA-0009", "up", 0, Map.of()),
                new Case("fail: the sink refuses a row", "", "", "bad-ids: 3", "", "PF-DATA-0009", "out", 0, Map.of()),
                new Case(
                        "fail: an expression error",
                        "",
                        "fail-at: 2, fail-with: coded",
                        "",
                        "",
                        "PF-DATA-0006",
                        "up",
                        0,
                        Map.of()),
                new Case(
                        "skip: a transform refuses two rows",
                        "",
                        "bad-ids: 3;7",
                        "",
                        SKIP,
                        null,
                        null,
                        8,
                        Map.of("up", 2L)),
                new Case(
                        "skip: an expression error",
                        "",
                        "fail-at: 2, fail-with: coded",
                        "",
                        SKIP,
                        null,
                        null,
                        9,
                        Map.of("up", 1L)),
                new Case(
                        "skip: the sink refuses a row in write and reports one at flush",
                        "",
                        "",
                        "bad-ids: 1, report-ids: 4",
                        SKIP,
                        null,
                        null,
                        8,
                        Map.of("out", 2L)),
                new Case(
                        "skip: the reader cannot read a record",
                        "bad-record-at: 5",
                        "",
                        "",
                        SKIP,
                        null,
                        null,
                        9,
                        Map.of("src", 1L)),
                new Case(
                        "skip up to a limit: more bad rows than allowed",
                        "",
                        "bad-ids: 1;2;3",
                        "",
                        "dirty: { policy: skip, max-rows: 2 }",
                        "PF-DATA-0010",
                        null,
                        0,
                        Map.of()),
                new Case(
                        "skip up to a limit: as many bad rows as allowed",
                        "",
                        "bad-ids: 1;2",
                        "",
                        "dirty: { policy: skip, max-rows: 2 }",
                        null,
                        null,
                        8,
                        Map.of("up", 2L)));
        for (boolean buffered : List.of(true, false)) {
            for (Case c : cases) {
                String name = c.name() + (buffered ? "" : ", without the write buffer");
                String probe = Probe.create();
                JobResult result = engine.submit(
                                TestConnectors.job(probe, c.source(), c.transform(), c.sink(), 1, c.env()),
                                SubmitOptions.defaults().withAsyncWriteBuffer(buffered))
                        .result()
                        .get(30, TimeUnit.SECONDS);
                JobMetrics metrics = result.metrics();
                if (c.error() != null) {
                    assertThat(result.status()).as(name).isEqualTo(JobStatus.FAILED);
                    assertThat(result.error().code().id()).as(name).isEqualTo(c.error());
                    assertThat(result.error().scope().node()).as(name).isEqualTo(c.errorNode());
                } else {
                    assertThat(result.status()).as(name + ": " + result.error()).isEqualTo(JobStatus.SUCCESS);
                    assertThat(metrics.rowsWritten()).as(name).isEqualTo(c.written());
                    assertThat(Probe.get(probe).rows).as(name).hasSize((int) c.written());
                    long total = c.dirty().values().stream()
                            .mapToLong(Long::longValue)
                            .sum();
                    assertThat(metrics.rowsDirty()).as(name).isEqualTo(total);
                    c.dirty().forEach((node, rows) -> assertThat(metrics.node(node))
                            .as(name + " at " + node)
                            .hasValueSatisfying(n -> assertThat(n.rowsDirty()).isEqualTo(rows)));
                    assertThat(metrics.dirtySamples()).as(name).hasSize((int) total);
                }
                assertThat(metrics.reconciled()).as(name + ": " + metrics).isTrue();
            }
        }
    }

    @Test
    void keepsSamplesOfTheBadRows() throws Exception {
        String probe = Probe.create();
        JobResult result = engine.submit(
                        TestConnectors.job(probe, "bad-record-at: 0, rows: 40", "bad-ids: 3", "", 1, SKIP),
                        SubmitOptions.defaults())
                .result()
                .get(30, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(JobStatus.SUCCESS);
        List<DirtySample> samples = result.metrics().dirtySamples();
        assertThat(samples).hasSize(2);
        assertThat(samples).anySatisfy(s -> {
            assertThat(s.node()).isEqualTo("src");
            assertThat(s.table()).isNull();
            assertThat(s.row()).isEmpty();
            assertThat(s.message()).contains("record 0 is not readable");
        });
        assertThat(samples).anySatisfy(s -> {
            assertThat(s.node()).isEqualTo("up");
            assertThat(s.code()).isEqualTo("PF-DATA-0009");
            assertThat(s.table()).isEqualTo("demo.numbers");
            assertThat(s.row()).isEqualTo("[3, name-3]");
            assertThat(s.message()).contains("'up' refused a row: id 3 cannot be transformed");
        });
    }

    @Test
    void keepsTwentySamplesAndCutsLongValues() throws Exception {
        String probe = Probe.create();
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            ids.append(i).append(';');
        }
        JobResult result = engine.submit(
                        TestConnectors.job(probe, "rows: 40, pad: 1000", "bad-ids: " + ids, "", 1, SKIP),
                        SubmitOptions.defaults())
                .result()
                .get(30, TimeUnit.SECONDS);
        assertThat(result.metrics().rowsDirty()).isEqualTo(30);
        assertThat(result.metrics().dirtySamples()).hasSize(DirtySample.MAX_SAMPLES);
        assertThat(result.metrics().dirtySamples().get(0).row())
                .hasSizeLessThan(DirtySample.MAX_VALUE_CHARS + 20)
                .endsWith("…]");
    }
}
