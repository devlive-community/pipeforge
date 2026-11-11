/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.devlive.pipeforge.api.row.Row;

/**
 * What the test connectors saw, by the {@code probe} option of the job, so that tests can look at
 * the rows written, the committables committed and the threads that ran each part.
 */
public final class Probe {

    private static final Map<String, Probe> PROBES = new ConcurrentHashMap<>();

    public final List<Row> rows = Collections.synchronizedList(new ArrayList<>());
    public final List<Object> schemaChanges = Collections.synchronizedList(new ArrayList<>());
    public final List<String> committed = Collections.synchronizedList(new ArrayList<>());
    public final List<String> globallyCommitted = Collections.synchronizedList(new ArrayList<>());
    public final Set<String> readerThreads = ConcurrentHashMap.newKeySet();
    public final Set<String> writerThreads = ConcurrentHashMap.newKeySet();
    public final Set<String> enumeratorThreads = ConcurrentHashMap.newKeySet();
    public final Set<Integer> splitsPerReader = ConcurrentHashMap.newKeySet();
    public final List<String> events = Collections.synchronizedList(new ArrayList<>());
    /** Counted down when a reader or writer starts blocking. */
    public final CountDownLatch blocking = new CountDownLatch(1);
    /** Lets a reader or writer that ignores cancellation go. */
    public final CountDownLatch release = new CountDownLatch(1);

    public volatile int commitAttempts;

    private Probe() {}

    /** A fresh probe under a new name. */
    public static String create() {
        String name = "probe-" + System.nanoTime();
        PROBES.put(name, new Probe());
        return name;
    }

    public static Probe get(String name) {
        Probe probe = PROBES.get(name);
        if (probe == null) {
            throw new IllegalStateException("No probe " + name);
        }
        return probe;
    }
}
