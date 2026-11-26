/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * What the faulty checkpoint storage of every process does now, one line in a file they all read
 * before each write: {@code ok}, {@code fail}, {@code slow <ms>} or {@code truncate}. A missing or
 * unreadable file is {@code ok}.
 */
public record StorageControl(Mode mode, long slowMs) {

    public enum Mode {
        OK,
        FAIL,
        SLOW,
        TRUNCATE
    }

    public static final StorageControl OK = new StorageControl(Mode.OK, 0);

    /** What {@code file} says. */
    public static StorageControl read(Path file) {
        String line;
        try {
            line = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8).strip()
                    : "";
        } catch (IOException e) {
            return OK;
        }
        String[] words = line.split("\\s+");
        return switch (words[0]) {
            case "fail" -> new StorageControl(Mode.FAIL, 0);
            case "truncate" -> new StorageControl(Mode.TRUNCATE, 0);
            case "slow" -> new StorageControl(Mode.SLOW, words.length > 1 ? Long.parseLong(words[1]) : 1000);
            default -> OK;
        };
    }

    /** Tells every process at once: written to a file next to it and moved over it. */
    public void write(Path file) throws IOException {
        String line =
                switch (mode) {
                    case OK -> "ok";
                    case FAIL -> "fail";
                    case TRUNCATE -> "truncate";
                    case SLOW -> "slow " + slowMs;
                };
        Path next = file.resolveSibling(file.getFileName() + ".next");
        Files.writeString(next, line + "\n", StandardCharsets.UTF_8);
        Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
