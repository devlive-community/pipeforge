/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.checkpoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;

/**
 * Keeps every split in exactly one snapshot (checkpoint design, section 3.4; protocol specification,
 * section 2.5). Once the enumerator's state of checkpoint {@code n} was taken, a split it assigns
 * afterwards is in no snapshot until the reader's of {@code n}: so the valve holds the split
 * events of a reader from the enumerator's snapshot until the reader acknowledged {@code n}, then
 * sends them in order. Events sent before the snapshot reach the readers before the trigger:
 * {@link #close} completes once they were delivered.
 *
 * <p>The methods run on the job master's thread; deliveries complete on any thread.
 */
public final class SplitAssignmentValve {

    /** Sends an event to a reader; completes once the reader's task executor took it. */
    @FunctionalInterface
    public interface Sender {
        CompletableFuture<Void> send(int reader, OperatorEvent event);
    }

    private final Sender sender;
    /** The checkpoint each closed reader waits for. */
    private final Map<Integer, Long> closed = new HashMap<>();

    private final Map<Integer, List<OperatorEvent>> held = new HashMap<>();
    private final Set<CompletableFuture<Void>> inFlight = ConcurrentHashMap.newKeySet();

    public SplitAssignmentValve(Sender sender) {
        this.sender = sender;
    }

    /** Sends a split event to a reader now, or once its valve opens. */
    public void send(int reader, OperatorEvent event) {
        if (closed.containsKey(reader)) {
            held.computeIfAbsent(reader, r -> new ArrayList<>()).add(event);
            return;
        }
        CompletableFuture<Void> delivered = sender.send(reader, event);
        inFlight.add(delivered);
        delivered.whenComplete((ignored, error) -> inFlight.remove(delivered));
    }

    /**
     * Closes the valves of {@code readers} for checkpoint {@code checkpointId}, right after the
     * enumerator's snapshot; completes once every event sent before was delivered, whether it was
     * or failed (a failed delivery fails its task).
     */
    public CompletableFuture<Void> close(long checkpointId, Iterable<Integer> readers) {
        readers.forEach(reader -> closed.merge(reader, checkpointId, Math::max));
        CompletableFuture<?>[] pending =
                inFlight.stream().map(f -> f.handle((v, e) -> null)).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(pending);
    }

    /** A reader acknowledged checkpoint {@code checkpointId}: its held events go out. */
    public void open(int reader, long checkpointId) {
        Long waiting = closed.get(reader);
        if (waiting == null || waiting > checkpointId) {
            // closed for a later checkpoint as well
            return;
        }
        closed.remove(reader);
        List<OperatorEvent> events = held.remove(reader);
        if (events != null) {
            events.forEach(event -> send(reader, event));
        }
    }

    /** Checkpoint {@code checkpointId} ended: every valve closed for it opens. */
    public void openAll(long checkpointId) {
        for (Integer reader : List.copyOf(closed.keySet())) {
            open(reader, checkpointId);
        }
    }

    /**
     * A reader restarts: the events held for it are dropped, as the splits in them go back to the
     * enumerator, and its valve opens.
     */
    public void drop(int reader) {
        closed.remove(reader);
        held.remove(reader);
    }

    /** The events held for a reader, for tests and views. */
    int held(int reader) {
        List<OperatorEvent> events = held.get(reader);
        return events == null ? 0 : events.size();
    }

    boolean isClosed(int reader) {
        return closed.containsKey(reader);
    }
}
