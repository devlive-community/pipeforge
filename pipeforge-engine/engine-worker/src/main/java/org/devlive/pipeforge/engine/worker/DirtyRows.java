/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.dirty.DirtySample;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The dirty data policy of a task. An error of kind {@code DATA} on one row is a bad row: under
 * {@code skip} the operator counts it and goes on, and the first {@link DirtySample#MAX_SAMPLES}
 * are kept as samples; under {@code fail}, and for any other error, the error stands. Callable from
 * the task thread and the write thread.
 */
final class DirtyRows {

    private static final Logger LOG = LoggerFactory.getLogger(DirtyRows.class);

    private final boolean skip;
    private final String task;
    private final List<DirtySample> samples = new ArrayList<>();

    DirtyRows(boolean skip, String task) {
        this.skip = skip;
        this.task = task;
    }

    /**
     * Whether {@code error} is a bad row that was skipped and counted at {@code node}; {@code false}
     * when the caller must throw it.
     *
     * @param row the row, or {@code null} when a reader could not read it as a row
     */
    boolean skipped(PipeForgeException error, @Nullable Row row, String node, OperatorMetrics metrics) {
        if (!skip || error.kind() != ErrorKind.DATA) {
            return false;
        }
        metrics.rowsDirty.increment();
        synchronized (samples) {
            if (samples.size() < DirtySample.MAX_SAMPLES) {
                if (samples.isEmpty()) {
                    LOG.warn("Task {} skips bad rows; the first at {}: {}", task, node, error.getMessage());
                }
                samples.add(new DirtySample(
                        node,
                        row == null ? null : String.valueOf(row.table()),
                        error.code().id(),
                        error.getMessage(),
                        row == null ? "" : text(row),
                        System.currentTimeMillis()));
            }
        }
        LOG.debug("Task {} skipped a bad row at {}: {}", task, node, error.getMessage());
        return true;
    }

    List<DirtySample> samples() {
        synchronized (samples) {
            return List.copyOf(samples);
        }
    }

    /** The values of a row, each cut to {@link DirtySample#MAX_VALUE_CHARS} characters. */
    static String text(Row row) {
        StringJoiner values = new StringJoiner(", ", "[", "]");
        for (int i = 0; i < row.arity(); i++) {
            Object value = row.get(i);
            String text = value instanceof byte[] bytes ? "<" + bytes.length + " bytes>" : String.valueOf(value);
            values.add(
                    text.length() > DirtySample.MAX_VALUE_CHARS
                            ? text.substring(0, DirtySample.MAX_VALUE_CHARS) + "…"
                            : text);
        }
        return values.toString();
    }
}
