/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.blackhole;

import java.util.List;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkContext;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.table.CatalogTable;

/** Discards rows; accepts every table, type, row kind and schema change, and keeps no state. */
final class BlackholeSink implements Sink<Void, Void> {

    private static final long serialVersionUID = 1L;

    private final double rowsPerSecond;

    BlackholeSink(double rowsPerSecond) {
        this.rowsPerSecond = rowsPerSecond;
    }

    double rowsPerSecond() {
        return rowsPerSecond;
    }

    @Override
    public void validate(List<CatalogTable> inputs, SinkContext context) {}

    @Override
    public SinkWriter<Void, Void> createWriter(WriterContext context, List<Void> restored) {
        Throttle throttle = rowsPerSecond > 0 ? Throttle.system(rowsPerSecond / context.parallelism()) : Throttle.NONE;
        return new BlackholeWriter(throttle);
    }

    @Override
    public VersionedSerializer<Void> committableSerializer() {
        return NothingSerializer.INSTANCE;
    }

    @Override
    public VersionedSerializer<Void> writerStateSerializer() {
        return NothingSerializer.INSTANCE;
    }

    /** The sink has neither committables nor state; nothing is ever serialized. */
    enum NothingSerializer implements VersionedSerializer<Void> {
        INSTANCE;

        @Override
        public int version() {
            return 1;
        }

        @Override
        public byte[] serialize(Void value) {
            return new byte[0];
        }

        @Override
        public Void deserialize(int version, byte[] bytes) {
            return null;
        }
    }
}
