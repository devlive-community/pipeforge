/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.junit.jupiter.api.Test;

/**
 * A range split is read in the order of its column and resumes after the last value it emitted:
 * every row once, even where many rows share a value (design 06 section 5).
 */
class JdbcResumeTest {

    static final TableId ITEMS = TableId.of("PUBLIC", "ITEMS");

    final H2 h2 = new H2("resume-" + System.nanoTime());

    /** The ids of the rows read, in order. */
    static final class Ids implements ReaderOutput {
        final List<Integer> ids = new ArrayList<>();

        @Override
        public void collect(Row row) {
            ids.add(((Number) row.get(0)).intValue());
        }

        @Override
        public void collect(Row row, long eventTimestamp) {
            collect(row);
        }

        @Override
        public void collect(SchemaChangeEvent event) {}
    }

    /** 3000 rows whose split column K takes each value for 300 rows, more than a poll emits. */
    JdbcSourceConfig groups(int maxGroupRows) throws Exception {
        h2.execute(
                "CREATE TABLE PUBLIC.ITEMS (ID INT PRIMARY KEY, K INT NOT NULL, NAME VARCHAR(20))",
                "INSERT INTO PUBLIC.ITEMS SELECT X, X / 300, 'item ' || X FROM SYSTEM_RANGE(0, 2999)");
        Map<String, Object> options = new HashMap<>(h2.options(ITEMS));
        options.put("split.column", "K");
        options.put("split.count", 2);
        options.put("resume.max-group-rows", maxGroupRows);
        return JdbcSourceConfig.of(ReadonlyConfig.of(options), H2.DIALECT, "src");
    }

    List<JdbcSplit> splits(JdbcSourceConfig config) {
        JdbcSplitEnumerator enumerator = new JdbcSplitEnumerator(
                config,
                H2.DIALECT,
                JdbcSplitProgressTest.enumeratorContext(),
                getClass().getClassLoader(),
                null);
        enumerator.start();
        return enumerator.snapshotState(1);
    }

    /**
     * Reads with a snapshot and a restore after every {@code every}-th poll, as a run that fails
     * that often would; returns the ids read in all.
     */
    List<Integer> readRestoringEvery(JdbcSourceConfig config, int every) throws Exception {
        Ids out = new Ids();
        List<JdbcSplit> state = splits(config);
        boolean ended = false;
        while (!ended) {
            JdbcSplitProgressTest.Context context = new JdbcSplitProgressTest.Context();
            try (JdbcSourceReader reader = new JdbcSourceReader(config, H2.DIALECT, context)) {
                reader.addSplits(state);
                reader.notifyNoMoreSplits();
                reader.start();
                for (int poll = 0; ; poll++) {
                    if (reader.pollNext(out) == PollStatus.END_OF_INPUT) {
                        ended = true;
                        break;
                    }
                    if (poll == every) {
                        // the state goes through the serializer, as a checkpoint takes it
                        state = new ArrayList<>();
                        for (JdbcSplit split : reader.snapshotState(1)) {
                            state.add(JdbcSplit.Serializer.INSTANCE.deserialize(
                                    3, JdbcSplit.Serializer.INSTANCE.serialize(split)));
                        }
                        break;
                    }
                }
            }
        }
        return out.ids;
    }

    @Test
    void aSplitResumesBetweenTwoValuesSoThatEveryRowIsReadOnce() throws Exception {
        JdbcSourceConfig config = groups(100_000);
        for (int every : new int[] {1, 2, 3}) {
            List<Integer> ids = readRestoringEvery(config, every);
            assertThat(ids)
                    .as("restored after every %d polls", every)
                    .hasSize(3000)
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void aValueWithTooManyRowsMayBeReadAgainButNoRowIsLost() throws Exception {
        List<Integer> ids = readRestoringEvery(groups(10), 1);
        assertThat(new java.util.TreeSet<>(ids)).as("every row").hasSize(3000);
        assertThat(ids.size()).as("some rows of a value were read again").isGreaterThan(3000);
    }

    @Test
    void theWatermarkNarrowsTheRangeAndTheRowsComeInOrder() {
        JdbcSplit range = new JdbcSplit("r", ITEMS, JdbcSplit.Kind.RANGE, "K", 10L, 20L);
        assertThat(range.ordered()).isTrue();
        assertThat(range.orderBy(H2.DIALECT)).isEqualTo(" ORDER BY \"K\"");
        JdbcSplit resumed = range.resumedAfter(14);
        assertThat(resumed.predicate(H2.DIALECT)).isEqualTo("\"K\" >= ? AND \"K\" < ? AND \"K\" > ?");
        assertThat(resumed.parameters()).containsExactly(10L, 20L, 14L);
        assertThat(new JdbcSplit("r", ITEMS, JdbcSplit.Kind.RANGE, "K", null, null)
                        .resumedAfter(3)
                        .predicate(H2.DIALECT))
                .isEqualTo("\"K\" IS NOT NULL AND \"K\" > ?");
        assertThat(new JdbcSplit("r", ITEMS, JdbcSplit.Kind.RANGE, "K", null, 5L)
                        .resumedAfter(3)
                        .parameters())
                .containsExactly(5L, 3L);

        assertThat(JdbcSplit.all(ITEMS).ordered())
                .as("a whole table resumes from its start")
                .isFalse();
        assertThat(new JdbcSplit("n", ITEMS, JdbcSplit.Kind.NULLS, "K", null, null).ordered())
                .isFalse();
        assertThat(JdbcSplit.all(null).orderBy(H2.DIALECT)).isEmpty();
    }

    @Test
    void splitsOfTheVersionBeforeWatermarksAreStillRead() throws Exception {
        JdbcSplit split = new JdbcSplit("r", ITEMS, JdbcSplit.Kind.RANGE, "K", 10L, 20L);
        // version 2 wrote no watermark after the bounds
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            JdbcSplit.Serializer.write(out, split);
        }
        byte[] v3 = bytes.toByteArray();
        byte[] v2 = java.util.Arrays.copyOf(v3, v3.length - 1);
        assertThat(JdbcSplit.Serializer.INSTANCE.deserialize(2, v2)).isEqualTo(split);

        JdbcSplit resumed = split.resumedAfter(12);
        assertThat(JdbcSplit.Serializer.INSTANCE.deserialize(3, JdbcSplit.Serializer.INSTANCE.serialize(resumed)))
                .isEqualTo(resumed);
        assertThat(JdbcSplit.ListSerializer.INSTANCE.deserialize(
                        3, JdbcSplit.ListSerializer.INSTANCE.serialize(List.of(split, resumed))))
                .containsExactly(split, resumed);
    }
}
