/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.transform;

import java.io.Serializable;
import java.util.List;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;

/** A stateless row transformation: map, filter or flat-map. */
@Stable
public interface Transform extends Serializable {

    /** The output tables for the given input tables; called while planning. */
    List<CatalogTable> transformSchema(List<CatalogTable> inputs);

    void open(TransformContext context) throws Exception;

    void process(Row row, Collector<Row> out) throws Exception;

    /** Maps a schema change the same way rows are mapped; the default passes it on unchanged. */
    default SchemaChangeEvent transformSchemaChange(SchemaChangeEvent event) {
        return event;
    }

    default void close() throws Exception {}
}
