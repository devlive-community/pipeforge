/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.devlive.pipeforge.engine.rpc.LogChunk;
import org.devlive.pipeforge.engine.rpc.LogQuery;

/**
 * Reads a task log. The log is the current file plus the rolled ones, each named with the offset it
 * starts at ({@code src-0-0.log.1048576}); offsets count from the start of the task's log, so they
 * stay valid when files roll, and an offset whose file was already deleted reads from the oldest one
 * left.
 */
final class LogFile {

    /** The start of an entry: a timestamp, then the level. Other lines continue the entry above. */
    private static final Pattern ENTRY =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?\\s+([A-Z]+)?");

    private static final int ATTEMPTS = 5;

    record Segment(long start, Path path, long size) {
        long end() {
            return start + size;
        }
    }

    private LogFile() {}

    static Path directory(Path file) {
        return java.util.Objects.requireNonNull(file.toAbsolutePath().getParent(), "a task log lives in a directory");
    }

    static Path rolled(Path active, long start) {
        return active.resolveSibling(active.getFileName() + "." + start);
    }

    /** The files of a log, oldest first; the last one is the current file, which may not exist yet. */
    static List<Segment> segments(Path active) throws IOException {
        String prefix = String.valueOf(active.getFileName()) + ".";
        Path dir = directory(active);
        List<Segment> segments = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, prefix + "*")) {
                for (Path file : files) {
                    String suffix = String.valueOf(file.getFileName()).substring(prefix.length());
                    if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
                        segments.add(new Segment(Long.parseLong(suffix), file, Files.size(file)));
                    }
                }
            }
        }
        segments.sort(Comparator.comparingLong(Segment::start));
        long start = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).end();
        long size = Files.exists(active) ? Files.size(active) : 0;
        segments.add(new Segment(start, active, size));
        return segments;
    }

    /**
     * Reads one chunk from {@code offset}.
     *
     * @param complete the task's log is closed: nothing will be added
     */
    static LogChunk read(Path active, long offset, LogQuery query, boolean complete) throws IOException {
        // a file may roll while it is read; the read then starts over with the new files
        for (int attempt = 1; ; attempt++) {
            try {
                return readOnce(active, offset, query, complete);
            } catch (NoSuchFileException e) {
                if (attempt == ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private static LogChunk readOnce(Path active, long offset, LogQuery query, boolean complete) throws IOException {
        List<Segment> segments = segments(active);
        long first = segments.get(0).start();
        long end = segments.get(segments.size() - 1).end();
        long from;
        boolean align;
        if (offset < 0) {
            from = Math.max(first, end + offset);
            align = from > first;
        } else {
            from = Math.min(Math.max(offset, first), end);
            align = false;
        }
        int length = (int) Math.min(query.maxBytes(), end - from);
        byte[] bytes = read(segments, from, length);
        boolean toEnd = from + length == end;

        int begin = 0;
        if (align) {
            // counted back from the end: start at the next whole line
            int newline = indexOf(bytes, (byte) '\n', 0);
            begin = newline < 0 ? bytes.length : newline + 1;
        }
        int stop = lastIndexOf(bytes, (byte) '\n') + 1;
        if (complete && toEnd) {
            stop = bytes.length;
        } else if (stop <= begin) {
            // a line longer than a chunk is sent in pieces; a line still being written waits
            stop = length == query.maxBytes() ? bytes.length : begin;
        }
        stop = Math.max(stop, begin);
        if (query.filters() && !(complete && toEnd)) {
            // keep the last entry whole for the next chunk, so that its lines are filtered together
            int last = lastEntryStart(bytes, begin, stop);
            if (last > begin) {
                stop = last;
            }
        }
        String text = new String(bytes, begin, stop - begin, StandardCharsets.UTF_8);
        long covered = from + stop;
        return new LogChunk(covered, query.filters() ? filter(text, query) : text, complete && covered == end);
    }

    private static byte[] read(List<Segment> segments, long from, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = from;
        for (Segment segment : segments) {
            if (!buffer.hasRemaining()) {
                break;
            }
            if (position >= segment.end() || segment.size() == 0) {
                continue;
            }
            try (FileChannel channel = FileChannel.open(segment.path(), StandardOpenOption.READ)) {
                long at = position - segment.start();
                while (buffer.hasRemaining() && at < segment.size()) {
                    int limit = (int) Math.min(buffer.remaining(), segment.size() - at);
                    ByteBuffer slice = buffer.slice(buffer.position(), limit);
                    int read = channel.read(slice, at);
                    if (read < 0) {
                        break;
                    }
                    buffer.position(buffer.position() + read);
                    at += read;
                    position += read;
                }
            }
        }
        // fewer bytes when a file was shorter than listed
        byte[] bytes = new byte[buffer.position()];
        buffer.flip().get(bytes);
        return bytes;
    }

    private static int lastEntryStart(byte[] bytes, int begin, int stop) {
        int last = -1;
        int line = begin;
        while (line < stop) {
            int newline = indexOf(bytes, (byte) '\n', line);
            int lineEnd = newline < 0 || newline >= stop ? stop : newline;
            if (ENTRY.matcher(new String(bytes, line, Math.min(lineEnd - line, 40), StandardCharsets.UTF_8))
                    .lookingAt()) {
                last = line;
            }
            line = lineEnd + 1;
        }
        return last;
    }

    /** The entries of {@code text} that match the query; lines without a timestamp belong to the entry above. */
    static String filter(String text, LogQuery query) {
        int min = query.minLevel() == null ? -1 : LogQuery.LEVELS.indexOf(query.minLevel());
        StringBuilder kept = new StringBuilder();
        StringBuilder entry = new StringBuilder();
        int level = -1;
        int at = 0;
        while (at < text.length()) {
            int newline = text.indexOf('\n', at);
            int next = newline < 0 ? text.length() : newline + 1;
            String line = text.substring(at, next);
            at = next;
            Matcher start = ENTRY.matcher(line);
            if (start.lookingAt()) {
                keep(kept, entry, level, min, query.contains());
                entry.setLength(0);
                String name = start.group(1);
                level = name == null ? -1 : LogQuery.LEVELS.indexOf(name);
            }
            entry.append(line);
        }
        keep(kept, entry, level, min, query.contains());
        return kept.toString();
    }

    private static void keep(StringBuilder kept, StringBuilder entry, int level, int min, String contains) {
        if (entry.isEmpty()) {
            return;
        }
        // an entry whose level is unknown passes only when no level is asked for
        boolean levelOk = min < 0 || level >= min;
        if (levelOk && (contains.isEmpty() || entry.indexOf(contains) >= 0)) {
            kept.append(entry);
        }
    }

    private static int indexOf(byte[] bytes, byte value, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] bytes, byte value) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
