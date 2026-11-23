/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.capability;

import org.devlive.pipeforge.api.annotation.Evolving;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * Capability of a factory, or of the source, sink or transform it creates: turns the exceptions
 * of a third-party library into registered errors, so that the engine knows whether to retry them.
 * For example a MySQL sink maps vendor error 1045 to {@code PF-AUTH-0001} (fatal) and 1213 (a
 * deadlock) to {@code PF-SINK-0001} marked {@link
 * org.devlive.pipeforge.common.error.ErrorKind#TRANSIENT}.
 *
 * <p>The engine asks the created component first, then the factory, for every exception that is
 * neither a {@link PipeForgeException} nor a {@link org.devlive.pipeforge.api.row.BadRowException}.
 * Exceptions nobody classifies become {@code PF-SRC-0001}, {@code PF-SINK-0001} or {@code
 * PF-PLUG-0007}, which are fatal. The engine adds the node and subtask to the returned error.
 */
@Evolving
public interface ErrorClassifier {

    /**
     * The registered error for {@code error}, or {@code null} to leave it to the engine. This must
     * not throw; an exception it throws is ignored.
     */
    @Nullable
    PipeForgeException classify(Throwable error);
}
