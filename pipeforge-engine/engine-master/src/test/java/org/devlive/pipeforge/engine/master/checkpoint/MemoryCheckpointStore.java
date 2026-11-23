/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.checkpoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/** A checkpoint store in memory, for tests; it can be made to fail. */
public final class MemoryCheckpointStore implements CheckpointStore {

    public final Map<String, Checkpoint> rows = new ConcurrentHashMap<>();
    final Map<String, Long> lastOfAttempt = new ConcurrentHashMap<>();
    volatile boolean down;

    private static String key(String runId, long id) {
        return runId + "/" + id;
    }

    private void check() {
        if (down) {
            throw PipeForgeException.of(ErrorCode.METASTORE_UNAVAILABLE)
                    .param("url", "jdbc:h2:mem:test")
                    .param("reason", "connection refused")
                    .build();
        }
    }

    public Checkpoint get(String runId, long id) {
        return rows.get(key(runId, id));
    }

    @Override
    public long lastCheckpointId(String runId) {
        check();
        return rows.values().stream()
                .filter(c -> c.runId().equals(runId))
                .mapToLong(Checkpoint::checkpointId)
                .max()
                .orElse(0);
    }

    @Override
    public void begin(Checkpoint checkpoint) {
        check();
        rows.put(key(checkpoint.runId(), checkpoint.checkpointId()), checkpoint);
    }

    @Override
    public void complete(
            String runId, long checkpointId, int attempt, Instant completedAt, long size, String tasksJson) {
        check();
        Checkpoint c = rows.get(key(runId, checkpointId));
        rows.put(
                key(runId, checkpointId),
                new Checkpoint(
                        runId,
                        checkpointId,
                        c.jobName(),
                        c.attempt(),
                        c.type(),
                        Status.COMPLETED,
                        c.reason(),
                        c.name(),
                        c.triggeredAt(),
                        completedAt,
                        size,
                        c.directory(),
                        null,
                        null,
                        tasksJson));
        lastOfAttempt.put(runId + "#" + attempt, checkpointId);
    }

    @Override
    public void fail(String runId, long checkpointId, Instant at, String errorCode, String errorMessage) {
        check();
        Checkpoint c = rows.get(key(runId, checkpointId));
        rows.put(
                key(runId, checkpointId),
                new Checkpoint(
                        runId,
                        checkpointId,
                        c.jobName(),
                        c.attempt(),
                        c.type(),
                        Status.FAILED,
                        c.reason(),
                        c.name(),
                        c.triggeredAt(),
                        at,
                        null,
                        c.directory(),
                        errorCode,
                        errorMessage,
                        null));
    }

    @Override
    public void delete(String runId, long checkpointId) {
        check();
        rows.remove(key(runId, checkpointId));
    }

    @Override
    public List<Checkpoint> list(String runId) {
        check();
        List<Checkpoint> list = new ArrayList<>(
                rows.values().stream().filter(c -> c.runId().equals(runId)).toList());
        list.sort(Comparator.comparingLong(Checkpoint::checkpointId).reversed());
        return list;
    }
}
