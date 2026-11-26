/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * What a run did, one JSON line per event in {@code injections.jsonl}: first the seed and the plan,
 * then every injection and every heal with when it happened and how it went, so that a failed run
 * shows what was done to the cluster and when.
 */
public final class ChaosRecord {

    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One event of a run.
     *
     * @param event {@code inject} or {@code heal}
     * @param atMs when it happened, after the start of the run
     * @param error why it went wrong; {@code null} when it went right
     */
    public record Event(String event, long atMs, Injection injection, @Nullable String error) {}

    private final Path file;
    private final List<Event> events = new CopyOnWriteArrayList<>();

    /** Starts the record of {@code plan} in {@code file}. */
    public ChaosRecord(Path file, String scenario, ChaosPlan plan) {
        this.file = file;
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("scenario", scenario);
        head.put("seed", plan.seed());
        head.put("startedAt", Instant.now().toString());
        head.put("replay", "-Dpipeforge.chaos.seed=" + plan.seed());
        head.put("plan", plan.injections());
        head.put("targets", plan.targets());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(head) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Adds an event, to memory and to the file. */
    public synchronized void add(Event event) {
        events.add(event);
        try {
            Files.writeString(
                    file, JSON.writeValueAsString(event) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public Path file() {
        return file;
    }
}
