/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.source.SourceSplit;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.testkit.ContractGroup;
import org.devlive.pipeforge.testkit.SinkUnderTest;
import org.devlive.pipeforge.testkit.SourceUnderTest;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.devlive.pipeforge.testkit.data.RowAssertions;
import org.devlive.pipeforge.testkit.data.TestData;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.junit.jupiter.api.DynamicNode;
import org.opentest4j.AssertionFailedError;

/**
 * The {@code checkpoint} and {@code state-compat} groups (checkpoint design, sections 6 and 10). A
 * source or sink that can be restored passes them; one that cannot declares them in {@code
 * skipped-testkit-groups} of its {@code plugin.yaml}, with the reason.
 *
 * <ul>
 *   <li>{@code checkpoint}: a snapshot in the middle of the work, through the plugin's serializers;
 *       every instance is dropped as a failure would; new ones restored from the states finish
 *       the work. A source reads every row exactly once in all; a sink with a committer writes
 *       every row exactly once, one without at least once.
 *   <li>{@code state-compat}: the states written by earlier versions of the plugin, kept in {@code
 *       src/test/resources/testkit-state/<plugin>/}, are read by this version and restored from. A version
 *       without its files fails; {@code -Dpipeforge.testkit.state.update=true} writes them.
 * </ul>
 */
public final class CheckpointGroups {

    /** The system property that writes the state files of the current serializer versions. */
    public static final String UPDATE_PROPERTY = "pipeforge.testkit.state.update";

    /** Where a plugin keeps the states of its earlier versions, relative to its module. */
    public static final Path STATES = Path.of("src", "test", "resources", "testkit-state");

    static final int ROWS = 2_000;

    private CheckpointGroups() {}

    // ------------------------------------------------------------------------------------ source

    public static ContractGroup sourceCheckpoint(SourceUnderTest source) {
        return new ContractGroup("checkpoint", engine -> {
            List<DynamicNode> cases = new ArrayList<>();
            for (int parallelism : List.of(1, 3)) {
                for (double at : List.of(0.1, 0.5, 0.9)) {
                    cases.add(Cases.test(
                            String.format(
                                    "restored after %d%% of %,d rows at parallelism %d",
                                    (int) (at * 100), ROWS, parallelism),
                            () -> restoreSource(source, parallelism, at)));
                }
            }
            return cases;
        });
    }

    record PlannedSource(TestTable table, JobPlan plan, FactoryContext context) {}

    static PlannedSource plan(SourceUnderTest source, String name, int rows, int parallelism) throws Exception {
        TestTable table = TestData.rows(name, TestData.wideTable(), rows, 11).keeping(source::holds);
        Map<String, Object> options = new LinkedHashMap<>(source.options(table));
        JobPlan plan = TestJob.named(name)
                .source(source.factory(), options)
                .sink(TestkitConnectors.collect(), Map.of("dataset", "unused"))
                .parallelism(parallelism)
                .plan();
        return new PlannedSource(table, plan, ApiDriver.context(plan, "src"));
    }

    static <S extends SourceSplit, E> void restoreSource(SourceUnderTest source, int parallelism, double at)
            throws Exception {
        PlannedSource planned = plan(source, "checkpoint", ROWS, parallelism);
        TestTable table = planned.table();
        try {
            List<Row> before;
            ApiDriver.SourceState<S, E> state;
            try (ApiDriver.SourceRun<S, E> first =
                    new ApiDriver.SourceRun<>(source.factory(), planned.context(), parallelism, null)) {
                assertTrue(first.readUntil((int) (table.rows().size() * at)), "the source stalled before the snapshot");
                state = first.snapshot(1);
                before = List.copyOf(first.rows);
            }
            List<Row> after;
            try (ApiDriver.SourceRun<S, E> restored =
                    new ApiDriver.SourceRun<>(source.factory(), planned.context(), parallelism, state)) {
                assertTrue(restored.readUntil(Integer.MAX_VALUE), "the restored source stalled");
                after = List.copyOf(restored.rows);
            }
            List<Row> all = new ArrayList<>(before);
            all.addAll(after);
            RowAssertions.assertSameRows(
                    "rows read before the snapshot (" + before.size() + ") and after the restore (" + after.size()
                            + ")",
                    table.table(),
                    table.rows(),
                    all);
        } finally {
            source.cleanup(table);
        }
    }

    public static ContractGroup sourceStateCompat(SourceUnderTest source) {
        return new ContractGroup(
                "state-compat",
                engine -> List.of(Cases.test(
                        "states of earlier versions are restored",
                        () -> sourceStates(
                                source,
                                STATES.resolve(source.factory().identifier()).resolve("source")))));
    }

    @SuppressWarnings("unchecked")
    static <S extends SourceSplit, E> void sourceStates(SourceUnderTest source, Path dir) throws Exception {
        PlannedSource planned = plan(source, "state-compat", 200, 1);
        TestTable table = planned.table();
        try {
            ApiDriver.SourceRun<S, E> probe = new ApiDriver.SourceRun<>(source.factory(), planned.context(), 1, null);
            VersionedSerializer<S> splits = probe.source.splitSerializer();
            VersionedSerializer<E> enumerator = probe.source.enumeratorStateSerializer();
            Path current = dir.resolve("v" + splits.version() + "-" + enumerator.version());
            if (Boolean.getBoolean(UPDATE_PROPERTY)) {
                assertTrue(probe.readUntil(table.rows().size() / 2), "the source stalled");
                drainSnapshot(probe, splits, enumerator, current);
            }
            probe.close();
            List<Path> versions = versions(dir);
            if (versions.stream().noneMatch(current::equals)) {
                throw new AssertionFailedError("No state of split serializer " + splits.version()
                        + " and enumerator serializer " + enumerator.version() + " in " + dir
                        + "; run the test once with -D" + UPDATE_PROPERTY + "=true and commit the files");
            }
            for (Path version : versions) {
                int[] numbers = numbers(version);
                E state = enumerator.deserialize(numbers[1], Files.readAllBytes(version.resolve("enumerator.bin")));
                List<S> restored = new ArrayList<>();
                for (byte[] split : ApiDriver.unframe(Files.readAllBytes(version.resolve("splits.bin")))) {
                    restored.add(splits.deserialize(numbers[0], split));
                }
                try (ApiDriver.SourceRun<S, E> run = new ApiDriver.SourceRun<>(
                        source.factory(),
                        planned.context(),
                        1,
                        new ApiDriver.SourceState<>(state, List.of(restored)))) {
                    assertTrue(run.readUntil(Integer.MAX_VALUE), "the source restored from " + version + " stalled");
                    assertEquals(
                            run.rows.size(),
                            new LinkedHashSet<>(run.rows).size(),
                            "rows read twice after restoring from " + version);
                    assertTrue(
                            table.rows().containsAll(run.rows),
                            "rows not in the table after restoring from " + version);
                }
            }
        } finally {
            source.cleanup(table);
        }
    }

    private static <S extends SourceSplit, E> void drainSnapshot(
            ApiDriver.SourceRun<S, E> run, VersionedSerializer<S> splits, VersionedSerializer<E> enumerator, Path dir)
            throws Exception {
        ApiDriver.SourceState<S, E> state = run.snapshot(1);
        List<byte[]> written = new ArrayList<>();
        for (S split : state.readers().get(0)) {
            written.add(splits.serialize(split));
        }
        Files.createDirectories(dir);
        Files.write(dir.resolve("splits.bin"), ApiDriver.frame(written));
        Files.write(dir.resolve("enumerator.bin"), enumerator.serialize(state.enumerator()));
    }

    private static final Pattern VERSION = Pattern.compile("v(\\d+)-(\\d+)");

    static List<Path> versions(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p ->
                            VERSION.matcher(String.valueOf(p.getFileName())).matches())
                    .sorted()
                    .toList();
        }
    }

    static int[] numbers(Path version) {
        Matcher matcher = VERSION.matcher(String.valueOf(version.getFileName()));
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.valueOf(version));
        }
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    // -------------------------------------------------------------------------------------- sink

    public static ContractGroup sinkCheckpoint(SinkUnderTest sink) {
        return new ContractGroup(
                "checkpoint",
                engine -> List.of(Cases.test(
                        String.format("a restored writer finishes %,d rows after a failure", ROWS),
                        () -> restoreSink(sink))));
    }

    record PlannedSink(TestTable table, Map<String, Object> options, FactoryContext context, String input) {}

    static PlannedSink plan(SinkUnderTest sink, String name, int rows) throws Exception {
        CatalogTable wide = TestData.wideTable();
        TestTable table = TestData.rows(name, wide, rows, 13).keeping(sink::holds);
        String in = Datasets.input(table);
        Map<String, Object> options = sink.options(table);
        JobPlan plan = TestJob.named(name)
                .source(TestkitConnectors.rows(), Map.of("dataset", in))
                .sink(sink.factory(), options)
                .plan();
        return new PlannedSink(table, options, ApiDriver.context(plan, "out"), in);
    }

    static <C, W> void restoreSink(SinkUnderTest sink) throws Exception {
        PlannedSink planned = plan(sink, "checkpoint-sink", ROWS);
        TestTable table = planned.table();
        List<Row> rows = table.rows();
        int half = rows.size() / 2;
        try {
            ApiDriver.SinkRun<C, W> run = new ApiDriver.SinkRun<>(sink.factory(), planned.context());
            SinkWriter<C, W> first = run.writer(0, List.of());
            for (Row row : rows.subList(0, half)) {
                first.write(row);
            }
            List<C> prepared = run.prepare(first, 1);
            List<W> state = run.snapshot(first, 1);
            // checkpoint 1 completes
            run.commit(prepared);
            // the rows after it are written but never prepared: the failure comes first
            for (Row row : rows.subList(half, half + (rows.size() - half) / 3)) {
                first.write(row);
            }
            first.close();

            SinkWriter<C, W> restored = run.writer(1, state);
            // a restore commits the restored checkpoint's committables again: it must be idempotent
            run.commit(prepared);
            for (Row row : rows.subList(half, rows.size())) {
                restored.write(row);
            }
            run.commit(run.prepare(restored, 2));
            restored.close();

            var written = sink.readBack(table, planned.options());
            if (written.isEmpty()) {
                return;
            }
            if (run.committer.isPresent() || run.global.isPresent()) {
                RowAssertions.assertSameRows("rows written exactly once", table.table(), rows, written.get());
            } else {
                RowAssertions.assertSameRows(
                        "rows written at least once (no committer)",
                        table.table(),
                        rows,
                        List.copyOf(new LinkedHashSet<>(written.get())));
            }
        } finally {
            Datasets.release(planned.input());
            sink.cleanup(table, planned.options());
        }
    }

    public static ContractGroup sinkStateCompat(SinkUnderTest sink) {
        return new ContractGroup(
                "state-compat",
                engine -> List.of(Cases.test(
                        "writer states of earlier versions are restored",
                        () -> sinkStates(
                                sink,
                                STATES.resolve(sink.factory().identifier()).resolve("sink")))));
    }

    static <C, W> void sinkStates(SinkUnderTest sink, Path dir) throws Exception {
        PlannedSink planned = plan(sink, "state-compat-sink", 200);
        TestTable table = planned.table();
        try {
            ApiDriver.SinkRun<C, W> run = new ApiDriver.SinkRun<>(sink.factory(), planned.context());
            VersionedSerializer<W> serializer = run.sink.writerStateSerializer();
            Path current = dir.resolve("v" + serializer.version());
            if (Boolean.getBoolean(UPDATE_PROPERTY)) {
                SinkWriter<C, W> writer = run.writer(0, List.of());
                for (Row row : table.rows().subList(0, table.rows().size() / 2)) {
                    writer.write(row);
                }
                run.prepare(writer, 1);
                List<byte[]> written = new ArrayList<>();
                for (W value : writer.snapshotState(1)) {
                    written.add(serializer.serialize(value));
                }
                writer.close();
                Files.createDirectories(current);
                Files.write(current.resolve("writer.bin"), ApiDriver.frame(written));
            }
            List<Path> versions;
            try (Stream<Path> entries = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
                versions = entries.filter(p -> String.valueOf(p.getFileName()).matches("v\\d+"))
                        .sorted()
                        .toList();
            }
            if (!versions.contains(current)) {
                throw new AssertionFailedError("No writer state of serializer " + serializer.version() + " in " + dir
                        + "; run the test once with -D" + UPDATE_PROPERTY + "=true and commit the files");
            }
            for (Path version : versions) {
                int number =
                        Integer.parseInt(String.valueOf(version.getFileName()).substring(1));
                List<W> state = new ArrayList<>();
                for (byte[] value : ApiDriver.unframe(Files.readAllBytes(version.resolve("writer.bin")))) {
                    state.add(serializer.deserialize(number, value));
                }
                SinkWriter<C, W> restored = run.writer(1, state);
                for (Row row : table.rows()
                        .subList(table.rows().size() / 2, table.rows().size())) {
                    restored.write(row);
                }
                run.commit(run.prepare(restored, 2));
                restored.close();
            }
        } finally {
            Datasets.release(planned.input());
            sink.cleanup(table, planned.options());
        }
    }
}
