/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import org.jspecify.annotations.Nullable;

/**
 * A cause that happened in another process, such as a task on a worker, known only by its text
 * ({@code Class: message}). Cause chains list the text as it was reported, so an error keeps its
 * causes when it crosses the RPC and is reported again.
 */
public final class RemoteCause extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String text;

    /**
     * @param text the cause as the other process described it, e.g. {@code java.io.IOException:
     *     connection reset}
     * @param next the cause below it, or {@code null}
     */
    public RemoteCause(String text, @Nullable Throwable next) {
        super(text, next, false, false);
        this.text = text;
    }

    /** The cause as the other process described it. */
    public String text() {
        return text;
    }

    /** Remote causes in the order of {@code chain}, from outer to inner; {@code null} for none. */
    public static @Nullable RemoteCause chain(java.util.List<String> chain) {
        RemoteCause next = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            next = new RemoteCause(chain.get(i), next);
        }
        return next;
    }
}
