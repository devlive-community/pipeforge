/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.List;
import java.util.Objects;

/** A message from the job master to an operator of a running task. */
public sealed interface OperatorEvent {

    /** The source node the event is for. */
    String node();

    /** Splits assigned to the reader, written with the source's split serializer. */
    record AddSplits(String node, List<SerializedValue> splits) implements OperatorEvent {
        public AddSplits {
            Objects.requireNonNull(node, "node");
            splits = List.copyOf(splits);
        }
    }

    /** The reader gets no more splits. */
    record NoMoreSplits(String node) implements OperatorEvent {
        public NoMoreSplits {
            Objects.requireNonNull(node, "node");
        }
    }
}
