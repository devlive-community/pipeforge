/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The events of a task: split assignments, timers, cancellation and later checkpoint triggers.
 * Any thread can put mail; only the task thread runs it, between two calls into the operators, so
 * that operators and their state are only ever touched by one thread and need no locks.
 */
final class Mailbox {

    /** A unit of work for the task thread. */
    @FunctionalInterface
    interface Mail {
        void run() throws Exception;
    }

    private final LinkedBlockingQueue<Mail> queue = new LinkedBlockingQueue<>();

    /** Adds mail; callable from any thread. */
    void put(Mail mail) {
        queue.add(mail);
    }

    /** Runs the pending mail on the calling thread; returns how many ran. */
    int drain() throws Exception {
        int count = 0;
        Mail mail;
        while ((mail = queue.poll()) != null) {
            mail.run();
            count++;
        }
        return count;
    }

    /**
     * Waits at most {@code timeoutNanos} for mail, then runs all pending mail. Used when the source
     * has nothing to read, so that new splits or a cancel are handled at once.
     *
     * @return how many mails ran
     */
    int waitAndDrain(long timeoutNanos) throws Exception {
        Mail first = queue.poll(timeoutNanos, TimeUnit.NANOSECONDS);
        if (first == null) {
            return 0;
        }
        first.run();
        return 1 + drain();
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }
}
