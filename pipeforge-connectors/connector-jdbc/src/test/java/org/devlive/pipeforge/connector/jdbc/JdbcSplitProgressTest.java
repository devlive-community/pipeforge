/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.ReaderEvent;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.junit.jupiter.api.Test;

/** The JDBC source says how many splits it makes and reports each split its reader read. */
class JdbcSplitProgressTest {

    final H2 h2 = new H2("progress-" + System.nanoTime());

    static final class Context implements ReaderContext {
        final List<String> finished = new ArrayList<>();

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
        public void sendSplitRequest() {}

        @Override
        public void sendReaderEvent(ReaderEvent event) {}

        @Override
        public void splitFinished(String splitId) {
            finished.add(splitId);
        }

        @Override
        public ClassLoader classLoader() {
            return getClass().getClassLoader();
        }
    }

    static final class Rows implements ReaderOutput {
        int count;

        @Override
        public void collect(Row row) {
            count++;
        }

        @Override
        public void collect(Row row, long eventTimestamp) {
            count++;
        }

        @Override
        public void collect(SchemaChangeEvent event) {}
    }

    static EnumeratorContext<JdbcSplit> enumeratorContext() {
        return new EnumeratorContext<>() {
            @Override
            public JobContext job() {
                return JobContext.local("t");
            }

            @Override
            public int parallelism() {
                return 1;
            }

            @Override
            public Set<Integer> registeredReaders() {
                return Set.of(0);
            }

            @Override
            public void assignSplits(Map<Integer, List<JdbcSplit>> assignment) {}

            @Override
            public void signalNoMoreSplits(int readerId) {}
        };
    }

    @Test
    void everySplitIsCountedAndReportedOnceItIsRead() throws Exception {
        h2.execute(
                "CREATE TABLE PUBLIC.ITEMS (ID INT PRIMARY KEY, NAME VARCHAR(20))",
                "INSERT INTO PUBLIC.ITEMS SELECT X, 'item ' || X FROM SYSTEM_RANGE(1, 1000)");
        Map<String, Object> options = new HashMap<>(h2.options(TableId.of("PUBLIC", "ITEMS")));
        options.put("split.count", 4);
        JdbcSourceConfig config = JdbcSourceConfig.of(ReadonlyConfig.of(options), H2.DIALECT, "src");

        JdbcSplitEnumerator enumerator = new JdbcSplitEnumerator(
                config, H2.DIALECT, enumeratorContext(), getClass().getClassLoader(), null);
        assertThat(enumerator.totalSplits()).as("unknown before the start").isEmpty();
        enumerator.start();
        List<JdbcSplit> splits = enumerator.snapshotState(1);
        assertThat(enumerator.totalSplits()).hasValue(splits.size());
        assertThat(splits).hasSizeGreaterThanOrEqualTo(4);

        Context context = new Context();
        Rows rows = new Rows();
        try (JdbcSourceReader reader = new JdbcSourceReader(config, H2.DIALECT, context)) {
            reader.start();
            reader.addSplits(splits);
            reader.notifyNoMoreSplits();
            while (reader.pollNext(rows) != PollStatus.END_OF_INPUT) {
                // read on
            }
        }
        assertThat(rows.count).isEqualTo(1000);
        assertThat(context.finished)
                .as("each split once, when read")
                .containsExactlyInAnyOrderElementsOf(
                        splits.stream().map(JdbcSplit::splitId).toList());

        assertThat(new JdbcSplitEnumerator(
                                config,
                                H2.DIALECT,
                                enumeratorContext(),
                                getClass().getClassLoader(),
                                splits)
                        .totalSplits())
                .as("restored: some splits were read before")
                .isEmpty();
    }
}
