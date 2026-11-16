/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What limits the speed of a run, with the metrics that show it and what to change.
 *
 * @param kind the conclusion
 * @param node the node the limit is at, or {@code null} when it is not one node
 * @param evidence the metrics the conclusion rests on
 * @param advice what to change, most promising first
 */
public record Bottleneck(Kind kind, @Nullable String node, List<Evidence> evidence, List<Advice> advice) {

    /** The conclusions, checked in this order; the first that holds is the result. */
    public enum Kind {
        /** The target, or the sink writing to it, is slower than the rest. */
        TARGET,
        /** Tasks wait for network buffers while the tasks they send to are not busy. */
        NETWORK,
        /** The source, or the reader of it, is slower than the rest. */
        SOURCE,
        /** Nothing waits for long: the run is as fast as its slowest part allows. */
        NONE,
        /** No task ran in the window yet. */
        UNKNOWN
    }

    /**
     * A metric a conclusion rests on.
     *
     * @param metric the name: a metric of the catalog, or a share such as {@code readerWaitShare}
     * @param node the node it was measured at, or {@code null} for the job
     * @param value the value; shares are from 0 to 1
     */
    public record Evidence(String metric, @Nullable String node, double value) {
        public Evidence {
            Objects.requireNonNull(metric, "metric");
        }
    }

    /**
     * A change that may help.
     *
     * @param code identifies the rule, see the run analysis documentation
     * @param text the change in English
     */
    public record Advice(String code, String text) {
        public Advice {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(text, "text");
        }
    }

    public Bottleneck {
        Objects.requireNonNull(kind, "kind");
        evidence = List.copyOf(evidence);
        advice = List.copyOf(advice);
    }
}
