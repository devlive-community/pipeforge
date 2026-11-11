/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.devlive.pipeforge.api.capability.SupportsMultiTable;
import org.devlive.pipeforge.api.capability.SupportsRowCount;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.PollStatus;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.ReaderOutput;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.api.source.SourceContext;
import org.devlive.pipeforge.api.source.SourceReader;
import org.devlive.pipeforge.api.source.SplitEnumerator;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.jspecify.annotations.Nullable;

/** The fake source (S01): generates or replays rows of one or more tables, in splits that readers pull. */
public final class FakeSource implements Source<FakeSplit, List<FakeSplit>>, SupportsMultiTable, SupportsRowCount {

    private static final long serialVersionUID = 1L;

    /** Rows a reader emits per poll at most, so that mail is handled between batches. */
    static final int BATCH = 512;

    private final transient FakeSettings settings;

    FakeSource(FakeSettings settings) {
        this.settings = settings;
    }

    FakeSettings settings() {
        return settings;
    }

    @Override
    public Boundedness boundedness() {
        return settings.unbounded() ? Boundedness.UNBOUNDED : Boundedness.BOUNDED;
    }

    @Override
    public List<CatalogTable> producedTables(SourceContext context) {
        return settings.tables().stream().map(FakeTable::table).toList();
    }

    @Override
    public long estimatedRowCount() {
        if (settings.unbounded()) {
            return -1;
        }
        return settings.tables().stream().mapToLong(FakeTable::rows).sum();
    }

    @Override
    public SplitEnumerator<FakeSplit, List<FakeSplit>> createEnumerator(EnumeratorContext<FakeSplit> context) {
        return new Enumerator(context, splits(settings));
    }

    @Override
    public SplitEnumerator<FakeSplit, List<FakeSplit>> restoreEnumerator(
            EnumeratorContext<FakeSplit> context, List<FakeSplit> state) {
        return new Enumerator(context, state);
    }

    @Override
    public SourceReader<FakeSplit> createReader(ReaderContext context) {
        double rate = settings.rowsPerSecond() <= 0 ? 0 : settings.rowsPerSecond() / context.parallelism();
        return new Reader(new RowGenerator(settings), context, rate);
    }

    @Override
    public VersionedSerializer<FakeSplit> splitSerializer() {
        return new FakeSplit.Serializer();
    }

    @Override
    public VersionedSerializer<List<FakeSplit>> enumeratorStateSerializer() {
        return new FakeSplit.ListSerializer();
    }

    /** The splits of every table: contiguous ranges, or interleaved endless ones when unbounded. */
    static List<FakeSplit> splits(FakeSettings settings) {
        List<FakeSplit> splits = new ArrayList<>();
        for (int t = 0; t < settings.tables().size(); t++) {
            String table = settings.tables().get(t).table().id().toString();
            if (settings.unbounded()) {
                for (int k = 0; k < settings.splits(); k++) {
                    splits.add(new FakeSplit(table + "#" + k, t, k, settings.splits(), -1));
                }
                continue;
            }
            long rows = settings.tables().get(t).rows();
            long count = Math.min(settings.splits(), Math.max(rows, 1));
            long size = rows / count;
            long extra = rows % count;
            long from = 0;
            for (int k = 0; k < count && from < rows; k++) {
                long length = size + (k < extra ? 1 : 0);
                splits.add(new FakeSplit(table + "#" + k, t, from, 1, length));
                from += length;
            }
        }
        return splits;
    }

    private static final class Enumerator implements SplitEnumerator<FakeSplit, List<FakeSplit>> {
        private final EnumeratorContext<FakeSplit> context;
        private final Deque<FakeSplit> pending;

        Enumerator(EnumeratorContext<FakeSplit> context, List<FakeSplit> splits) {
            this.context = context;
            this.pending = new ArrayDeque<>(splits);
        }

        @Override
        public void start() {}

        @Override
        public void handleSplitRequest(int readerId) {
            FakeSplit next = pending.poll();
            if (next == null) {
                context.signalNoMoreSplits(readerId);
            } else {
                context.assignSplit(next, readerId);
            }
        }

        @Override
        public void addSplitsBack(List<FakeSplit> splits, int readerId) {
            splits.forEach(pending::addFirst);
        }

        @Override
        public void addReader(int readerId) {}

        @Override
        public List<FakeSplit> snapshotState(long checkpointId) {
            return List.copyOf(pending);
        }
    }

    /** Emits the rows of one split at a time and asks for the next when it is done. */
    static final class Reader implements SourceReader<FakeSplit> {
        private final RowGenerator generator;
        private final ReaderContext context;
        private final Deque<FakeSplit> splits = new ArrayDeque<>();
        private final RateLimiter limiter;
        private @Nullable FakeSplit current;
        private boolean noMore;

        Reader(RowGenerator generator, ReaderContext context, double rowsPerSecond) {
            this.generator = generator;
            this.context = context;
            this.limiter = new RateLimiter(rowsPerSecond);
        }

        @Override
        public void start() {
            context.sendSplitRequest();
        }

        @Override
        public void addSplits(List<FakeSplit> added) {
            splits.addAll(added);
        }

        @Override
        public void notifyNoMoreSplits() {
            noMore = true;
        }

        @Override
        public PollStatus pollNext(ReaderOutput output) {
            FakeSplit split = current;
            if (split == null) {
                split = splits.poll();
                if (split == null) {
                    return noMore ? PollStatus.END_OF_INPUT : PollStatus.NOTHING_AVAILABLE;
                }
            }
            long budget = split.remaining() < 0 ? BATCH : Math.min(BATCH, split.remaining());
            budget = limiter.acquire(budget);
            if (budget == 0) {
                current = split;
                return PollStatus.NOTHING_AVAILABLE;
            }
            long row = split.next();
            for (long i = 0; i < budget; i++, row += split.stride()) {
                long time = generator.eventTime(split.table(), row);
                if (time >= 0) {
                    output.collect(generator.row(split.table(), row), time);
                } else {
                    output.collect(generator.row(split.table(), row));
                }
            }
            split = split.advance(budget);
            if (split.done()) {
                current = null;
                context.sendSplitRequest();
            } else {
                current = split;
            }
            return PollStatus.MORE_AVAILABLE;
        }

        @Override
        public List<FakeSplit> snapshotState(long checkpointId) {
            List<FakeSplit> state = new ArrayList<>();
            FakeSplit split = current;
            if (split != null) {
                state.add(split);
            }
            state.addAll(splits);
            return state;
        }
    }

    /** A token bucket that allows no bursts beyond a tenth of a second of rows. */
    static final class RateLimiter {
        private final double rate;
        private final double capacity;
        private double tokens;
        private long last = System.nanoTime();

        RateLimiter(double rowsPerSecond) {
            this.rate = rowsPerSecond;
            this.capacity = Math.max(1, rowsPerSecond / 10);
        }

        /** Takes up to {@code wanted} rows; 0 when the reader must wait. */
        long acquire(long wanted) {
            if (rate <= 0) {
                return wanted;
            }
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - last) / 1e9 * rate);
            last = now;
            long granted = Math.min(wanted, (long) tokens);
            tokens -= granted;
            return granted;
        }
    }
}
