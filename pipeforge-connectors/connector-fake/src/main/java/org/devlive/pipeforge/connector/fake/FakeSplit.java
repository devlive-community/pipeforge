/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.source.SourceSplit;

/**
 * Rows {@code next, next + stride, next + 2 * stride, ...} of a table: {@code remaining} of them, or
 * forever when {@code remaining} is -1. Bounded splits are contiguous ranges (stride 1); unbounded
 * splits interleave, so that every row index belongs to exactly one split.
 *
 * @param splitId unique within the source
 * @param table the index of the table in the source
 * @param next the next row to emit
 * @param stride the distance between two rows of the split
 * @param remaining rows left, or -1 for unbounded
 */
record FakeSplit(String splitId, int table, long next, long stride, long remaining) implements SourceSplit {

    boolean done() {
        return remaining == 0;
    }

    /** The split after {@code count} rows were emitted. */
    FakeSplit advance(long count) {
        return new FakeSplit(splitId, table, next + count * stride, stride, remaining < 0 ? -1 : remaining - count);
    }

    static final class Serializer implements VersionedSerializer<FakeSplit> {
        @Override
        public int version() {
            return 1;
        }

        @Override
        public byte[] serialize(FakeSplit split) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                write(out, split);
            }
            return bytes.toByteArray();
        }

        @Override
        public FakeSplit deserialize(int version, byte[] bytes) throws IOException {
            check(version);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                return read(in);
            }
        }

        static void write(DataOutputStream out, FakeSplit split) throws IOException {
            out.writeUTF(split.splitId());
            out.writeInt(split.table());
            out.writeLong(split.next());
            out.writeLong(split.stride());
            out.writeLong(split.remaining());
        }

        static FakeSplit read(DataInputStream in) throws IOException {
            return new FakeSplit(in.readUTF(), in.readInt(), in.readLong(), in.readLong(), in.readLong());
        }

        static void check(int version) throws IOException {
            if (version != 1) {
                throw new IOException("Unknown fake split version " + version);
            }
        }
    }

    /** The enumerator state: the splits not yet assigned. */
    static final class ListSerializer implements VersionedSerializer<List<FakeSplit>> {
        @Override
        public int version() {
            return 1;
        }

        @Override
        public byte[] serialize(List<FakeSplit> splits) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(splits.size());
                for (FakeSplit split : splits) {
                    Serializer.write(out, split);
                }
            }
            return bytes.toByteArray();
        }

        @Override
        public List<FakeSplit> deserialize(int version, byte[] bytes) throws IOException {
            Serializer.check(version);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int count = in.readInt();
                List<FakeSplit> splits = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    splits.add(Serializer.read(in));
                }
                return splits;
            }
        }
    }
}
