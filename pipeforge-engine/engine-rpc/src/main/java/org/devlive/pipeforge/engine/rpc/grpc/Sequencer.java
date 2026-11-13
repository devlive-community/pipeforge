/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Hands events over in the order of their sequence numbers, per stream (a task attempt and node):
 * duplicates are dropped, and an event that arrives before an earlier one waits for it. Sequence
 * numbers of a stream start at 1 (protocol specification, section 2.5).
 *
 * @param <K> what identifies a stream
 * @param <E> the events
 */
public final class Sequencer<K, E> {

    private static final class Stream<E> {
        long next = 1;
        final TreeMap<Long, E> waiting = new TreeMap<>();
    }

    private final Map<K, Stream<E>> streams = new HashMap<>();

    /**
     * Accepts event {@code sequence} of {@code stream} and passes every event that is now in order
     * to {@code deliver}, in order; returns how many it passed.
     */
    public synchronized int accept(K stream, long sequence, E event, Consumer<E> deliver) {
        Stream<E> state = streams.computeIfAbsent(stream, k -> new Stream<>());
        if (sequence < state.next || state.waiting.containsKey(sequence)) {
            return 0;
        }
        state.waiting.put(sequence, event);
        int delivered = 0;
        while (!state.waiting.isEmpty() && state.waiting.firstKey() == state.next) {
            deliver.accept(state.waiting.pollFirstEntry().getValue());
            state.next++;
            delivered++;
        }
        return delivered;
    }

    /** Forgets the streams that {@code ended} selects, e.g. those of a finished task. */
    public synchronized void forget(java.util.function.Predicate<K> ended) {
        streams.keySet().removeIf(ended);
    }
}
