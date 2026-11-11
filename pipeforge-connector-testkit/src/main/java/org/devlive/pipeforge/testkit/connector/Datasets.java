/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit.connector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.testkit.data.TestTable;

/**
 * The data the built-in testkit connectors read and write, by data set id. Jobs refer to a data
 * set with the {@code dataset} option; everything stays in this JVM.
 */
public final class Datasets {

    private static final AtomicLong NEXT = new AtomicLong();
    private static final Map<String, TestTable> INPUTS = new ConcurrentHashMap<>();
    private static final Map<String, List<Row>> OUTPUTS = new ConcurrentHashMap<>();
    private static final Map<String, List<SchemaChangeEvent>> CHANGES = new ConcurrentHashMap<>();

    private Datasets() {}

    /** Registers rows for {@code testkit-rows} to read; returns the data set id. */
    public static String input(TestTable table) {
        String id = "in-" + NEXT.incrementAndGet() + "-" + table.name().replaceAll("[^A-Za-z0-9]+", "-");
        INPUTS.put(id, table);
        return id;
    }

    /** A new, empty data set for {@code testkit-collect} to write into; returns its id. */
    public static String output() {
        String id = "out-" + NEXT.incrementAndGet();
        OUTPUTS.put(id, Collections.synchronizedList(new ArrayList<>()));
        CHANGES.put(id, Collections.synchronizedList(new ArrayList<>()));
        return id;
    }

    static TestTable table(String id) {
        TestTable table = INPUTS.get(id);
        if (table == null) {
            throw new IllegalArgumentException("No testkit data set '" + id + "'");
        }
        return table;
    }

    static List<Row> sink(String id) {
        List<Row> rows = OUTPUTS.get(id);
        if (rows == null) {
            throw new IllegalArgumentException("No testkit output '" + id + "'");
        }
        return rows;
    }

    static List<SchemaChangeEvent> changes(String id) {
        List<SchemaChangeEvent> changes = CHANGES.get(id);
        if (changes == null) {
            throw new IllegalArgumentException("No testkit output '" + id + "'");
        }
        return changes;
    }

    /** The rows written into an output data set so far. */
    public static List<Row> written(String id) {
        List<Row> rows = sink(id);
        synchronized (rows) {
            return List.copyOf(rows);
        }
    }

    /** The schema changes written into an output data set so far. */
    public static List<SchemaChangeEvent> schemaChanges(String id) {
        List<SchemaChangeEvent> changes = changes(id);
        synchronized (changes) {
            return List.copyOf(changes);
        }
    }

    /** Forgets a data set. */
    public static void release(String id) {
        INPUTS.remove(id);
        OUTPUTS.remove(id);
        CHANGES.remove(id);
    }
}
