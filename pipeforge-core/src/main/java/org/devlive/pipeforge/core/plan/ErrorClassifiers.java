/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import org.devlive.pipeforge.api.capability.ErrorClassifier;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/** Asks the plugin objects that implement {@link ErrorClassifier} what an exception means. */
public final class ErrorClassifiers {

    private ErrorClassifiers() {}

    /**
     * The error the first of {@code candidates} that implements {@link ErrorClassifier} and knows
     * {@code error} returns, or {@code null}. A classifier that throws is skipped, and what it threw
     * is added to {@code error} as suppressed. Call it with the
     * plugin's context class loader set.
     */
    public static @Nullable PipeForgeException classify(Throwable error, @Nullable Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof ErrorClassifier classifier) {
                try {
                    PipeForgeException classified = classifier.classify(error);
                    if (classified != null) {
                        return classified;
                    }
                } catch (RuntimeException | LinkageError e) {
                    if (e != error) {
                        error.addSuppressed(e);
                    }
                }
            }
        }
        return null;
    }
}
