/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import java.util.EnumSet;
import java.util.Set;

/**
 * Something faults are injected into.
 *
 * @param name the process ({@code worker-1}), the link ({@code worker-1->master}) or the storage
 * @param faults the faults it may get, all of its kind
 */
public record Target(String name, Kind kind, Set<Fault> faults) {

    /** What a target is. */
    public enum Kind {
        PROCESS,
        LINK,
        STORAGE
    }

    public Target {
        faults = Set.copyOf(faults);
        for (Fault fault : faults) {
            if (fault.kind() != kind) {
                throw new IllegalArgumentException(fault + " does not apply to a " + kind + " like " + name);
            }
        }
        if (faults.isEmpty()) {
            throw new IllegalArgumentException(name + " gets no fault");
        }
    }

    /** A process that may be killed and stopped. */
    public static Target process(String name) {
        return new Target(name, Kind.PROCESS, EnumSet.of(Fault.KILL, Fault.TERM));
    }

    /** A link that may be cut, slowed down and lose packets. */
    public static Target link(String name) {
        return new Target(name, Kind.LINK, EnumSet.of(Fault.PARTITION, Fault.DELAY, Fault.LOSS));
    }

    /** The checkpoint storage, which may fail, stall and keep half of a file. */
    public static Target storage(String name) {
        return new Target(
                name, Kind.STORAGE, EnumSet.of(Fault.STORAGE_FAIL, Fault.STORAGE_SLOW, Fault.STORAGE_TRUNCATE));
    }
}
