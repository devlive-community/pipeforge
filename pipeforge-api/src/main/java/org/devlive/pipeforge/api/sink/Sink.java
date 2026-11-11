/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.sink;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.table.CatalogTable;

/** A destination: parallel writers and optional committers for exactly-once delivery. */
@Stable
public interface Sink<CommT, WriterStateT> extends Serializable {

    /** Checks the input tables, keys and capabilities while planning. */
    void validate(List<CatalogTable> inputs, SinkContext context);

    SinkWriter<CommT, WriterStateT> createWriter(WriterContext context, List<WriterStateT> restored) throws Exception;

    default Optional<SinkCommitter<CommT>> createCommitter() {
        return Optional.empty();
    }

    default Optional<GlobalCommitter<CommT, ?>> createGlobalCommitter() {
        return Optional.empty();
    }

    VersionedSerializer<CommT> committableSerializer();

    VersionedSerializer<WriterStateT> writerStateSerializer();
}
