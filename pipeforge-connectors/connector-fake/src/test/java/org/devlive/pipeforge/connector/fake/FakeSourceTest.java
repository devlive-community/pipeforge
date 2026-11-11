/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.ReaderEvent;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.source.SourceReader;
import org.devlive.pipeforge.api.source.SplitEnumerator;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

/** The fake source without the engine: settings, splits, the reader and the enumerator. */
class FakeSourceTest {

    private static FakeSource source(Map<String, Object> options) {
        return new FakeSource(FakeConfig.read(ReadonlyConfig.of(options), "src"));
    }

    /** Captures what a reader emits, with event times. */
    private static final class Capture implements ReaderOutput {
        final List<Row> rows = new ArrayList<>();
        final List<Long> times = new ArrayList<>();

        @Override
        public void collect(Row row) {
            rows.add(row);
            times.add(-1L);
        }

        @Override
        public void collect(Row row, long eventTimestamp) {
            rows.add(row);
            times.add(eventTimestamp);
        }

        @Override
        public void collect(SchemaChangeEvent event) {
            throw new AssertionError("no schema changes");
        }
    }

    private static final class Context implements ReaderContext {
        int requests;

        @Override
        public JobContext job() {
            return JobContext.local("t");
        }

        @Override
        public int subtaskIndex() {
            return 0;
        }

        @Override
        public int parallelism() {
            return 1;
        }

        @Override
        public void sendSplitRequest() {
            requests++;
        }

        @Override
        public void sendReaderEvent(ReaderEvent event) {}

        @Override
        public ClassLoader classLoader() {
            return getClass().getClassLoader();
        }
    }

    /** Reads every split of {@code source} with one reader. */
    private static Capture readAll(FakeSource source) throws Exception {
        Capture capture = new Capture();
        Context context = new Context();
        try (SourceReader<FakeSplit> reader = source.createReader(context)) {
            reader.start();
            reader.addSplits(FakeSource.splits(source.settings()));
            reader.notifyNoMoreSplits();
            PollStatus status;
            do {
                status = reader.pollNext(capture);
            } while (status != PollStatus.END_OF_INPUT);
        }
        return capture;
    }

    @Test
    void cutsTablesIntoBalancedSplits() {
        List<FakeSplit> splits =
                FakeSource.splits(source(Map.of("rows", 10, "splits", 4)).settings());
        assertThat(splits).extracting(FakeSplit::remaining).containsExactly(3L, 3L, 2L, 2L);
        assertThat(splits).extracting(FakeSplit::next).containsExactly(0L, 3L, 6L, 8L);
        assertThat(FakeSource.splits(source(Map.of("rows", 2, "splits", 4)).settings()))
                .hasSize(2);
        assertThat(FakeSource.splits(source(Map.of("rows", 0)).settings())).isEmpty();

        List<FakeSplit> endless =
                FakeSource.splits(source(Map.of("unbounded", true, "splits", 3)).settings());
        assertThat(endless).extracting(FakeSplit::next).containsExactly(0L, 1L, 2L);
        assertThat(endless).allSatisfy(s -> {
            assertThat(s.stride()).isEqualTo(3);
            assertThat(s.remaining()).isEqualTo(-1);
        });
        assertThat(endless.get(1).advance(2).next()).isEqualTo(7);
        assertThat(endless.get(1).advance(2).done()).isFalse();
    }

    @Test
    void estimatesItsRowCount() {
        assertThat(source(Map.of("rows", 10, "tables", List.of("a.b", "a.c"))).estimatedRowCount())
                .isEqualTo(20);
        assertThat(source(Map.of("unbounded", true)).estimatedRowCount()).isEqualTo(-1);
        assertThat(source(Map.of()).maxTables()).isEqualTo(-1);
    }

    @Test
    void stampsRowsWithEventTimesThatCanArriveLate() throws Exception {
        Capture ordered = readAll(source(Map.of(
                "rows",
                50,
                "splits",
                1,
                "event-time-column",
                "created_at",
                "columns",
                List.of("id INT64 NOT NULL", "created_at TIMESTAMP(3)"),
                "event-time-step",
                "2s",
                "start-time",
                "2026-03-01T00:00:00Z")));
        long start = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli();
        for (int i = 0; i < 50; i++) {
            assertThat(ordered.times.get(i)).isEqualTo(start + i * 2000L);
            assertThat(ordered.rows.get(i).get(1))
                    .isEqualTo(LocalDateTime.ofInstant(Instant.ofEpochMilli(start + i * 2000L), ZoneOffset.UTC));
        }

        Capture late = readAll(source(Map.of(
                "rows",
                500,
                "splits",
                1,
                "event-time-column",
                "ts",
                "columns",
                List.of("id INT64 NOT NULL", "ts INT64"),
                "max-out-of-order",
                "5s")));
        int outOfOrder = 0;
        long base = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        for (int i = 0; i < 500; i++) {
            long time = late.times.get(i);
            assertThat(time).isBetween(base + i * 1000L - 5000, base + i * 1000L);
            assertThat(late.rows.get(i).get(1)).isEqualTo(time);
            if (i > 0 && time < late.times.get(i - 1)) {
                outOfOrder++;
            }
        }
        assertThat(outOfOrder).isPositive();

        Capture ltz = readAll(source(Map.of(
                "rows", 3, "event-time-column", "at", "columns", List.of("id INT64 NOT NULL", "at TIMESTAMP_LTZ(3)"))));
        assertThat(ltz.rows.get(0).get(1)).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void takesEventTimesOfLiteralRowsFromTheirColumn() throws Exception {
        Capture capture = readAll(source(Map.of(
                "columns", List.of("id INT64", "ts TIMESTAMP(3)"),
                "data", List.of("[1, \"2026-01-01T00:00:01\"]", "[2, null]"),
                "event-time-column", "ts")));

        assertThat(capture.times)
                .containsExactly(Instant.parse("2026-01-01T00:00:01Z").toEpochMilli(), -1L);
    }

    @Test
    void readerWaitsForSplitsAndKeepsItsProgress() throws Exception {
        FakeSource source = source(Map.of("rows", 2000, "splits", 1));
        Context context = new Context();
        SourceReader<FakeSplit> reader = source.createReader(context);
        reader.start();
        Capture capture = new Capture();

        assertThat(reader.pollNext(capture)).isEqualTo(PollStatus.NOTHING_AVAILABLE);
        reader.addSplits(FakeSource.splits(source.settings()));
        assertThat(reader.pollNext(capture)).isEqualTo(PollStatus.MORE_AVAILABLE);
        assertThat(capture.rows).hasSize(FakeSource.BATCH);
        assertThat(reader.snapshotState(1)).singleElement().satisfies(split -> {
            assertThat(split.next()).isEqualTo(FakeSource.BATCH);
            assertThat(split.remaining()).isEqualTo(2000 - FakeSource.BATCH);
        });
        while (capture.rows.size() < 2000) {
            reader.pollNext(capture);
        }
        assertThat(context.requests).isEqualTo(2);
        assertThat(reader.snapshotState(2)).isEmpty();
        reader.notifyNoMoreSplits();
        assertThat(reader.pollNext(capture)).isEqualTo(PollStatus.END_OF_INPUT);
    }

    @Test
    void enumeratorHandsOutSplitsAndTakesThemBack() throws Exception {
        FakeSource source = source(Map.of("rows", 10, "splits", 2));
        Map<Integer, List<FakeSplit>> assigned = new HashMap<>();
        List<Integer> finished = new ArrayList<>();
        EnumeratorContext<FakeSplit> context = new EnumeratorContext<>() {
            @Override
            public JobContext job() {
                return JobContext.local("t");
            }

            @Override
            public int parallelism() {
                return 2;
            }

            @Override
            public Set<Integer> registeredReaders() {
                return Set.of(0, 1);
            }

            @Override
            public void assignSplits(Map<Integer, List<FakeSplit>> assignment) {
                assignment.forEach((r, s) ->
                        assigned.computeIfAbsent(r, k -> new ArrayList<>()).addAll(s));
            }

            @Override
            public void signalNoMoreSplits(int readerId) {
                finished.add(readerId);
            }
        };
        SplitEnumerator<FakeSplit, List<FakeSplit>> enumerator = source.createEnumerator(context);
        enumerator.start();
        enumerator.addReader(0);
        enumerator.handleSplitRequest(0);
        assertThat(enumerator.snapshotState(1)).hasSize(1);
        enumerator.addSplitsBack(assigned.get(0), 0);
        enumerator.handleSplitRequest(1);
        enumerator.handleSplitRequest(1);
        enumerator.handleSplitRequest(1);

        assertThat(assigned.get(1)).extracting(FakeSplit::splitId).containsExactly("fake.rows#0", "fake.rows#1");
        assertThat(finished).containsExactly(1);
        SplitEnumerator<FakeSplit, List<FakeSplit>> restored =
                source.restoreEnumerator(context, List.of(assigned.get(1).get(0)));
        assertThat(restored.snapshotState(2)).hasSize(1);
    }

    @Test
    void serializesSplitsAndTheEnumeratorState() throws IOException {
        FakeSplit split = new FakeSplit("t#1", 2, 30, 4, -1);
        FakeSplit.Serializer serializer = new FakeSplit.Serializer();
        assertThat(serializer.deserialize(1, serializer.serialize(split))).isEqualTo(split);
        FakeSplit.ListSerializer list = new FakeSplit.ListSerializer();
        assertThat(list.deserialize(1, list.serialize(List.of(split, split.advance(1)))))
                .hasSize(2);
        assertThatThrownBy(() -> serializer.deserialize(2, new byte[0])).isInstanceOf(IOException.class);
        FakeSource source = source(Map.of());
        assertThat(source.splitSerializer().version()).isEqualTo(1);
        assertThat(source.enumeratorStateSerializer().version()).isEqualTo(1);
    }

    @Test
    void rateLimiterGrantsRowsAsTimePasses() throws InterruptedException {
        FakeSource.RateLimiter unlimited = new FakeSource.RateLimiter(0);
        assertThat(unlimited.acquire(512)).isEqualTo(512);
        FakeSource.RateLimiter limited = new FakeSource.RateLimiter(1000);
        assertThat(limited.acquire(512)).isZero();
        Thread.sleep(50);
        long granted = limited.acquire(512);
        assertThat(granted).isBetween(1L, 100L);
    }

    @Test
    void reportsConfigurationProblemsAsInvalidOptions() {
        Map<Map<String, Object>, String> cases = new java.util.LinkedHashMap<>();
        cases.put(Map.of("table", "a.b", "tables", List.of("a.c")), "tables: set either table or tables, not both");
        cases.put(Map.of("tables", List.of()), "tables: list at least one table");
        cases.put(Map.of("tables", List.of("a.b", "a.b")), "tables: table a.b is listed twice");
        cases.put(Map.of("table", "a.b.c.d.e"), "table: 'a.b.c.d.e' is not a table name");
        cases.put(Map.of("columns", List.of("id")), "columns: 'id': write the column as 'name TYPE'");
        cases.put(Map.of("columns", List.of("id NUMBERISH")), "columns: 'id NUMBERISH': ");
        cases.put(Map.of("columns", List.of("`id INT64")), "columns: '`id INT64': the quoted name has no closing");
        cases.put(Map.of("columns", List.of("a INT32", "a INT64")), "columns: column a is listed twice");
        cases.put(Map.of("columns", List.of()), "columns: list at least one column");
        cases.put(Map.of("primary-key", List.of("nope")), "primary-key: there is no column nope");
        cases.put(Map.of("data", List.of("[]"), "rows", 3), "data: set either rows or data, not both");
        cases.put(
                Map.of("data", List.of("[]"), "tables", List.of("a.b", "a.c")),
                "data: literal rows need a single table");
        cases.put(Map.of("data", List.of("[]"), "unbounded", true), "data: literal rows cannot be unbounded");
        cases.put(Map.of("data", List.of("[1, 2")), "data: row 1 is not JSON");
        cases.put(
                Map.of("data", List.of("{}")),
                "data: row 1 must be a JSON array of 4 values ([id, name, amount, created_at])");
        cases.put(
                Map.of("data", List.of("[1, \"a\", \"1.5\", null]", "[2, \"b\", \"1.555\", null]")),
                "data: row 2, column amount: 1.555 has more than 2 decimal places");
        cases.put(Map.of("event-time-column", "nope"), "event-time-column: there is no column nope");
        cases.put(Map.of("event-time-column", "name"), "event-time-column: column name is STRING(32); use TIMESTAMP");
        cases.put(
                Map.of("event-time-column", "created_at", "start-time", "yesterday"),
                "start-time: expected an instant");
        cases.forEach((options, message) -> assertThatThrownBy(() -> source(options))
                .as(message)
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code().id()).isEqualTo("PF-CONF-0002");
                    assertThat(e.params().get("option") + ": " + e.params().get("reason"))
                            .startsWith(message);
                    assertThat(e.scope().node()).isEqualTo("src");
                }));
    }

    @Test
    void acceptsQuotedColumnNames() {
        assertThat(FakeConfig.column("`order date` DATE NOT NULL").name()).isEqualTo("order date");
        assertThat(source(Map.of("columns", List.of("`a b` INT32"), "primary-key", List.of("a b")))
                        .settings()
                        .tables()
                        .get(0)
                        .table()
                        .primaryKey())
                .containsExactly("a b");
        assertThatThrownBy(() -> FakeConfig.column("`` INT32")).hasMessageContaining("name TYPE");
    }
}
