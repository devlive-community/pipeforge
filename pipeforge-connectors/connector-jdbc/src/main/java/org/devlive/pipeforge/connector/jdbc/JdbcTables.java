/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.devlive.pipeforge.api.row.TableId;

/** Finds the tables {@code tables.include} and {@code tables.exclude} select, from JDBC metadata. */
public final class JdbcTables {

    /** The default name of the progress table of a JDBC sink, which a source never reads. */
    static final String PROGRESS_TABLE = "_pf_progress";

    private JdbcTables() {}

    /**
     * The tables whose qualified names ({@code db.table}) match one of {@code include} entirely and
     * none of {@code exclude}, outside the system schemas, sorted by name. The progress tables of
     * JDBC sinks, by their default name, are never included.
     */
    public static List<TableId> discover(
            Connection connection, JdbcDialect dialect, List<String> include, List<String> exclude)
            throws SQLException {
        List<Pattern> in = include.stream().map(Pattern::compile).toList();
        List<Pattern> out = exclude.stream().map(Pattern::compile).toList();
        List<TableId> tables = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String catalog = rs.getString("TABLE_CAT");
                String schema = rs.getString("TABLE_SCHEM");
                TableId table = dialect.tableId(catalog, schema, rs.getString("TABLE_NAME"));
                String place = table.database() != null ? table.database() : "";
                if (dialect.systemSchemas().contains(place.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                if (table.table().equalsIgnoreCase(PROGRESS_TABLE)) {
                    continue;
                }
                String name = table.toQualifiedName();
                if (in.stream().anyMatch(p -> p.matcher(name).matches())
                        && out.stream().noneMatch(p -> p.matcher(name).matches())) {
                    tables.add(table);
                }
            }
        }
        tables.sort(Comparator.comparing(TableId::toQualifiedName));
        return tables;
    }
}
