/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.util.List;
import java.util.Optional;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkCommitter;
import org.devlive.pipeforge.api.sink.SinkContext;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.table.CatalogTable;

/** A sink whose target is down ({@code type: down}): its writers cannot start, so runs fail. */
public final class DownSinkFactory implements SinkFactory {

    @Override
    public String identifier() {
        return "down";
    }

    @Override
    public OptionRule optionRule() {
        return OptionRule.builder().build();
    }

    @Override
    public Sink<?, ?> createSink(FactoryContext context) {
        return new Down();
    }

    static final class Down implements Sink<String, Void> {
        private static final long serialVersionUID = 1L;

        @Override
        public void validate(List<CatalogTable> inputs, SinkContext context) {}

        @Override
        public SinkWriter<String, Void> createWriter(WriterContext context, List<Void> restored) {
            throw new IllegalStateException("the target is down");
        }

        @Override
        public Optional<SinkCommitter<String>> createCommitter() {
            return Optional.empty();
        }

        @Override
        public VersionedSerializer<String> committableSerializer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VersionedSerializer<Void> writerStateSerializer() {
            throw new UnsupportedOperationException();
        }
    }
}
