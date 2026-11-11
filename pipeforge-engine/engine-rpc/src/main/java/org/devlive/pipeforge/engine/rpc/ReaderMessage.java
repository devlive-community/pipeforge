/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.Objects;
import org.devlive.pipeforge.api.source.ReaderEvent;

/** A message from a source reader to its enumerator in the job master. */
public sealed interface ReaderMessage {

    /** The task the reader runs in. */
    ExecutionAttemptId task();

    /** The source node. */
    String node();

    /** The reader asks for another split. */
    record SplitRequest(ExecutionAttemptId task, String node) implements ReaderMessage {
        public SplitRequest {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(node, "node");
        }
    }

    /** A connector-specific event, e.g. the high watermark of a finished snapshot chunk. */
    record Custom(ExecutionAttemptId task, String node, ReaderEvent event) implements ReaderMessage {
        public Custom {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(event, "event");
        }
    }
}
