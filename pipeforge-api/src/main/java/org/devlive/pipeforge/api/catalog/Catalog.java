/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.catalog;

import java.util.List;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;

/** Metadata access: browse databases and tables, create, drop and change tables. */
@Stable
public interface Catalog extends AutoCloseable {

    void open() throws Exception;

    List<String> listDatabases();

    /** Tables of a database whose names match a pattern ({@code *} matches any characters). */
    List<TableId> listTables(String database, String pattern);

    boolean tableExists(TableId id);

    CatalogTable getTable(TableId id);

    void createTable(CatalogTable table, boolean ignoreIfExists);

    void dropTable(TableId id, boolean ignoreIfNotExists);

    void truncateTable(TableId id);

    default void applySchemaChange(SchemaChangeEvent event) {
        throw new UnsupportedOperationException("Schema changes are not supported by this catalog");
    }

    @Override
    default void close() throws Exception {}
}
