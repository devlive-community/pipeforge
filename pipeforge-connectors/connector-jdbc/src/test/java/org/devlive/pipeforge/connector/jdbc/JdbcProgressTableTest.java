/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

/**
 * A sink with a progress table commits the source's positions with the rows, so that a task that
 * fails after the last checkpoint, and reads on from the position recorded, writes every row once
 * (design 06 section 5.4). The test drives reader and writer as the engine does.
 */
class JdbcProgressTableTest {

    static final TableId SRC = TableId.of("PUBLIC", "SRC");
    static final TableId DST = TableId.of("PUBLIC", "DST");
    static final int ROWS = 3000;

    final H2 h2 = new H2("progress-table-" + System.nanoTime());

    static final WriterContext WRITER = new WriterContext() {
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
        public ClassLoader classLoader() {
            return JdbcProgressTableTest.class.getClassLoader();
        }
    };

    /** Hands rows and positions to a writer, in order, as the chain of a task does. */
    static final class Chain implements ReaderOutput {
        final JdbcBatchWriter writer;
        boolean keeping;

        Chain(JdbcBatchWriter writer) {
            this.writer = writer;
        }

        @Override
        public void collect(Row row) {
            writer.write(row);
        }

        @Override
        public void collect(SchemaChangeEvent event) {}

        @Override
        public void progress(String splitId, String position) {
            if (keeping) {
                writer.progress(splitId, position);
            }
        }
    }

    void tables() throws Exception {
        h2.execute(
                "CREATE TABLE PUBLIC.SRC (ID INT PRIMARY KEY, NAME VARCHAR(20))",
                "INSERT INTO PUBLIC.SRC SELECT X, 'item ' || X FROM SYSTEM_RANGE(0, " + (ROWS - 1) + ")",
                "CREATE TABLE PUBLIC.DST (ID INT, NAME VARCHAR(20))");
    }

    JdbcSourceConfig source() {
        Map<String, Object> options = new HashMap<>(h2.options(SRC));
        options.put("split.count", 2);
        return JdbcSourceConfig.of(ReadonlyConfig.of(options), H2.DIALECT, "src");
    }

    JdbcSink sink(Map<String, Object> extra) throws Exception {
        Map<String, Object> options = new HashMap<>(h2.options(DST));
        options.put("batch-size", 100);
        options.putAll(extra);
        CatalogTable input;
        try (Connection connection = h2.connect()) {
            input = JdbcSchemas.describe(connection, H2.DIALECT, SRC, "h2").table();
        }
        return new JdbcSink(
                JdbcSinkConfig.of(ReadonlyConfig.of(options), H2.DIALECT, "dst"), H2.DIALECT, List.of(input));
    }

    List<Integer> written() throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (Connection connection = h2.connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT ID FROM PUBLIC.DST")) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        return ids;
    }

    int progressRows() throws Exception {
        try (Connection connection = h2.connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM PUBLIC.\"_pf_progress\"")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Reads a checkpoint's worth, takes the reader's state, reads {@code after} more polls, then
     * stops without flushing, as a task that is killed; a second attempt continues from the
     * checkpoint's state and the recorded positions.
     */
    void killAndRestart(int after) throws Exception {
        JdbcSourceConfig config = source();
        JdbcSource source = new JdbcSource(config, H2.DIALECT);
        JdbcSplitEnumerator enumerator = new JdbcSplitEnumerator(
                config,
                H2.DIALECT,
                JdbcSplitProgressTest.enumeratorContext(),
                getClass().getClassLoader(),
                null);
        enumerator.start();
        List<JdbcSplit> state = enumerator.snapshotState(1);
        JdbcSink sink = sink(Map.of());

        // the first attempt: a checkpoint after two polls, then a kill
        JdbcBatchWriter first = (JdbcBatchWriter) sink.createWriter(WRITER, List.of());
        Chain chain = new Chain(first);
        chain.keeping = first.startProgress("run-1", "dst");
        assertThat(chain.keeping).isTrue();
        try (JdbcSourceReader reader = new JdbcSourceReader(config, H2.DIALECT, new JdbcSplitProgressTest.Context())) {
            reader.addSplits(state);
            reader.notifyNoMoreSplits();
            reader.start();
            reader.pollNext(chain);
            reader.pollNext(chain);
            // the barrier: the sink flushes, the reader's state is taken
            first.flush();
            state = reader.snapshotState(1);
            for (int i = 0; i < after; i++) {
                reader.pollNext(chain);
            }
        }
        first.close();

        // the second attempt: the checkpoint's splits, moved on to where the sink recorded them
        JdbcBatchWriter second = (JdbcBatchWriter) sink.createWriter(WRITER, List.of());
        Chain again = new Chain(second);
        again.keeping = second.startProgress("run-1", "dst");
        List<String> ids = state.stream().map(JdbcSplit::splitId).toList();
        Map<String, String> recorded = second.recordedProgress(ids);
        List<JdbcSplit> resumed = new ArrayList<>();
        for (JdbcSplit split : state) {
            String position = recorded.get(split.splitId());
            resumed.add(position == null ? split : source.resume(split, position));
        }
        try (JdbcSourceReader reader = new JdbcSourceReader(config, H2.DIALECT, new JdbcSplitProgressTest.Context())) {
            reader.addSplits(resumed);
            reader.notifyNoMoreSplits();
            reader.start();
            while (reader.pollNext(again) != PollStatus.END_OF_INPUT) {
                // read on
            }
        }
        second.prepareCommit(Long.MAX_VALUE);
        second.forgetProgress();
        second.close();
    }

    @Test
    void aTaskKilledAfterTheCheckpointWritesEveryRowOnceAfterItsRestart() throws Exception {
        for (int after : new int[] {0, 1, 3, 6}) {
            h2.execute("DROP TABLE IF EXISTS PUBLIC.SRC", "DROP TABLE IF EXISTS PUBLIC.DST");
            tables();
            killAndRestart(after);
            assertThat(written())
                    .as("killed %d polls after the checkpoint", after)
                    .hasSize(ROWS)
                    .doesNotHaveDuplicates();
            assertThat(progressRows())
                    .as("forgotten once the input was written")
                    .isZero();
        }
    }

    @Test
    void positionsCommitWithTheRowsBeforeThem() throws Exception {
        tables();
        JdbcBatchWriter writer = (JdbcBatchWriter) sink(Map.of()).createWriter(WRITER, List.of());
        assertThat(writer.startProgress("run-1", "dst")).isTrue();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rows.add(org.devlive.pipeforge.api.row.GenericRow.of(SRC, i, "item " + i));
        }
        rows.forEach(writer::write);
        assertThat(written()).as("a full batch waits for a position").isEmpty();
        writer.progress("s1", "249");
        assertThat(written()).hasSize(250);
        assertThat(writer.recordedProgress(List.of("s1", "s2"))).containsExactly(Map.entry("s1", "249"));
        writer.close();

        // another node or run sees nothing of it
        JdbcBatchWriter other = (JdbcBatchWriter) sink(Map.of()).createWriter(WRITER, List.of());
        assertThat(other.startProgress("run-2", "dst")).isTrue();
        assertThat(other.recordedProgress(List.of("s1"))).isEmpty();
        other.close();
    }

    @Test
    void aProgressTableThatCannotBeCreatedLetsAutoWriteWithoutProgressAndOnFail() throws Exception {
        tables();
        // a name the database refuses, as a missing permission would refuse the table
        Map<String, Object> refused = Map.of("resume.progress-table-name", "p".repeat(300));

        JdbcBatchWriter auto = (JdbcBatchWriter) sink(refused).createWriter(WRITER, List.of());
        assertThat(auto.startProgress("run-1", "dst")).isFalse();
        auto.write(org.devlive.pipeforge.api.row.GenericRow.of(SRC, 1, "one"));
        auto.flush();
        assertThat(written()).containsExactly(1);
        auto.close();

        Map<String, Object> required = new HashMap<>(refused);
        required.put("resume.progress-table", "on");
        JdbcBatchWriter on = (JdbcBatchWriter) sink(required).createWriter(WRITER, List.of());
        assertThatThrownBy(() -> on.startProgress("run-1", "dst")).isInstanceOf(PipeForgeException.class);
        on.close();

        JdbcBatchWriter off =
                (JdbcBatchWriter) sink(Map.of("resume.progress-table", "off")).createWriter(WRITER, List.of());
        assertThat(off.startProgress("run-1", "dst")).isFalse();
        off.close();
    }
}
