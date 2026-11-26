/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

/** Applies the faults of a plan to a cluster, and heals them. */
public interface Faults {

    /** Starts {@code injection}. */
    void inject(Injection injection) throws Exception;

    /** Ends {@code injection}: the process runs again, the link and the storage work again. */
    void heal(Injection injection) throws Exception;
}
