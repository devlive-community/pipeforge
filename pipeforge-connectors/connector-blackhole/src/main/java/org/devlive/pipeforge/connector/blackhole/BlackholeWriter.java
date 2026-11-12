/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.blackhole;

import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;

/**
 * Discards rows. Without a rate limit a write does nothing at all, so a job with this sink measures
 * the engine and its source.
 */
final class BlackholeWriter implements SinkWriter<Void, Void> {

    private final Throttle throttle;

    BlackholeWriter(Throttle throttle) {
        this.throttle = throttle;
    }

    @Override
    public void write(Row row) throws InterruptedException {
        throttle.acquire();
    }

    /** Schema changes are discarded like rows. */
    @Override
    public void applySchemaChange(SchemaChangeEvent event) {}

    @Override
    public void flush() {}
}
