/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The states of a run and the transitions between them (job DSL and control plane design, section
 * 3.2), as far as this version needs them: no restarts or retries of runs yet. Every transition is
 * checked and recorded with its time and reason; an illegal one is a defect and refused.
 */
public final class RunStates {

    /** Where a run stands. */
    public enum State {
        /** Accepted; waiting for slots. */
        QUEUE,
        RUNNING,
        CANCELLING,
        SUCCESS,
        FAILED,
        CANCELLED,
        /** Cancelled because it ran longer than its timeout. */
        TIMEOUT,
        /** Taking the savepoint it pauses with. */
        PAUSING,
        /** Stopped with a savepoint, holding no slots; it goes on when it is resumed. */
        PAUSED;

        public boolean isTerminal() {
            return this == SUCCESS || this == FAILED || this == CANCELLED || this == TIMEOUT;
        }
    }

    /** One change of state. */
    public record Transition(@Nullable State from, State to, Instant at, String reason) {}

    private static final Map<State, Set<State>> ALLOWED = Map.of(
            State.QUEUE, EnumSet.of(State.RUNNING, State.CANCELLING, State.FAILED),
            State.RUNNING, EnumSet.of(State.SUCCESS, State.FAILED, State.CANCELLING, State.PAUSING),
            State.CANCELLING, EnumSet.of(State.CANCELLED, State.FAILED, State.TIMEOUT),
            // a savepoint that fails leaves the run running
            State.PAUSING, EnumSet.of(State.PAUSED, State.RUNNING, State.FAILED, State.CANCELLING),
            State.PAUSED, EnumSet.of(State.QUEUE, State.CANCELLED),
            State.SUCCESS, EnumSet.noneOf(State.class),
            State.FAILED, EnumSet.noneOf(State.class),
            State.CANCELLED, EnumSet.noneOf(State.class),
            State.TIMEOUT, EnumSet.noneOf(State.class));

    private final List<Transition> transitions = new ArrayList<>();
    private State state;

    /** A run that was just accepted. */
    public RunStates(Instant at) {
        this.state = State.QUEUE;
        transitions.add(new Transition(null, State.QUEUE, at, "submitted"));
    }

    public synchronized State state() {
        return state;
    }

    /** Every transition, the first being the submission. */
    public synchronized List<Transition> transitions() {
        return List.copyOf(transitions);
    }

    /** Whether a run may go from {@code from} to {@code to}. */
    public static boolean allowed(State from, State to) {
        return ALLOWED.get(from).contains(to);
    }

    /**
     * Moves the run to {@code next}; moving to the state it is in does nothing.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public synchronized void move(State next, Instant at, String reason) {
        if (next == state) {
            return;
        }
        if (!allowed(state, next)) {
            throw new IllegalStateException("A run cannot go from " + state + " to " + next);
        }
        transitions.add(new Transition(state, next, at, reason));
        state = next;
    }
}
