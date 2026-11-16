/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import org.jspecify.annotations.Nullable;

/**
 * The logback appender that writes the events of task threads into the logs of their tasks; events
 * of other threads are left to the other appenders. Configured in {@code logback.xml}:
 *
 * <pre>{@code
 * <appender name="TASKS" class="org.devlive.pipeforge.engine.worker.log.TaskLogAppender">
 *     <encoder><pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern></encoder>
 * </appender>
 * }</pre>
 *
 * <p>Readers filter by level only when a line starts with the timestamp and then the level, as in
 * this pattern.
 */
public final class TaskLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private @Nullable Encoder<ILoggingEvent> encoder;

    public void setEncoder(Encoder<ILoggingEvent> encoder) {
        this.encoder = encoder;
    }

    @Override
    public void start() {
        if (encoder == null) {
            addError("No encoder set for the task log appender " + name);
            return;
        }
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String key = event.getMDCPropertyMap().get(LogService.MDC_LOG);
        Encoder<ILoggingEvent> current = encoder;
        if (key != null && current != null) {
            LogService.append(key, current.encode(event));
        }
    }
}
