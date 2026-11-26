/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

/** What the chaos framework does to a process, a link or the checkpoint storage. */
public enum Fault {
    /** SIGKILL the process, as a crash or a lost host; it is started again when the fault heals. */
    KILL(Target.Kind.PROCESS),
    /** SIGTERM the process, which stops on its own; it is started again when the fault heals. */
    TERM(Target.Kind.PROCESS),
    /** The link accepts no connection and drops the ones it carries. */
    PARTITION(Target.Kind.LINK),
    /** Every chunk the link carries waits {@code param} milliseconds. */
    DELAY(Target.Kind.LINK),
    /**
     * A share of {@code param} percent of the chunks the link carries waits 200 ms, as TCP does
     * when it resends a lost packet; bytes are never dropped, TCP would not let them be.
     */
    LOSS(Target.Kind.LINK),
    /** Writes to the checkpoint storage fail. */
    STORAGE_FAIL(Target.Kind.STORAGE),
    /** Every write to the checkpoint storage waits {@code param} milliseconds. */
    STORAGE_SLOW(Target.Kind.STORAGE),
    /** Writes to the checkpoint storage keep only the first half of a file and report success. */
    STORAGE_TRUNCATE(Target.Kind.STORAGE);

    private final Target.Kind kind;

    Fault(Target.Kind kind) {
        this.kind = kind;
    }

    /** What the fault applies to. */
    public Target.Kind kind() {
        return kind;
    }
}
