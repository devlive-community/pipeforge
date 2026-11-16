/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker.log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The open log of one task: appends whole events and rolls the file when it is full. */
final class TaskLog {

    private final Path path;
    private final long maxFileBytes;
    private final int maxFiles;
    private long start;
    private long size;
    private @Nullable OutputStream out;
    private volatile boolean closed;
    private boolean failed;

    TaskLog(Path path, long maxFileBytes, int maxFiles) throws IOException {
        this.path = path;
        this.maxFileBytes = maxFileBytes;
        this.maxFiles = maxFiles;
        Files.createDirectories(LogFile.directory(path));
        List<LogFile.Segment> segments = LogFile.segments(path);
        LogFile.Segment active = segments.get(segments.size() - 1);
        this.start = active.start();
        this.size = active.size();
        this.out = new FileOutputStream(path.toFile(), true);
    }

    Path path() {
        return path;
    }

    boolean closed() {
        return closed;
    }

    /** Writes an event; a full file is rolled first, so an event is never split over two files. */
    synchronized void append(byte[] event) {
        OutputStream current = out;
        if (closed || current == null || failed) {
            return;
        }
        try {
            if (size > 0 && size + event.length > maxFileBytes) {
                current = roll(current);
            }
            current.write(event);
            size += event.length;
        } catch (IOException e) {
            // the logging of a task must not fail the task: the log stops here
            failed = true;
            System.err.println("Writing the task log " + path + " failed, it stops here: " + e);
        }
    }

    private OutputStream roll(OutputStream current) throws IOException {
        current.close();
        out = null;
        Files.move(path, LogFile.rolled(path, start), StandardCopyOption.ATOMIC_MOVE);
        start += size;
        size = 0;
        List<LogFile.Segment> segments = LogFile.segments(path);
        // the active file is the last one; drop the oldest beyond the limit
        for (int i = 0; i < segments.size() - maxFiles; i++) {
            Files.deleteIfExists(segments.get(i).path());
        }
        OutputStream next = new FileOutputStream(path.toFile(), true);
        out = next;
        return next;
    }

    synchronized void close() {
        closed = true;
        OutputStream current = out;
        out = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                System.err.println("Closing the task log " + path + " failed: " + e);
            }
        }
    }
}
