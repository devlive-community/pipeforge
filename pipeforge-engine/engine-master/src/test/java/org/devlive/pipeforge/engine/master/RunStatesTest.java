/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.engine.master.RunStates.State;
import org.devlive.pipeforge.engine.master.RunStates.Transition;
import org.junit.jupiter.api.Test;

class RunStatesTest {

    /** The transitions of the job DSL and control plane design, section 3.2, that this version has. */
    static final Map<State, Set<State>> EXPECTED = Map.of(
            State.QUEUE, EnumSet.of(State.RUNNING, State.CANCELLING, State.FAILED),
            State.RUNNING, EnumSet.of(State.SUCCESS, State.FAILED, State.CANCELLING, State.PAUSING),
            State.CANCELLING, EnumSet.of(State.CANCELLED, State.FAILED, State.TIMEOUT),
            State.PAUSING, EnumSet.of(State.PAUSED, State.RUNNING, State.FAILED, State.CANCELLING),
            State.PAUSED, EnumSet.of(State.QUEUE, State.CANCELLED));

    @Test
    void allowsExactlyTheTransitionsOfTheDesign() {
        for (State from : State.values()) {
            for (State to : State.values()) {
                boolean expected = EXPECTED.getOrDefault(from, Set.of()).contains(to);
                assertThat(RunStates.allowed(from, to)).as("%s -> %s", from, to).isEqualTo(expected);
            }
        }
        assertThat(EnumSet.allOf(State.class))
                .filteredOn(State::isTerminal)
                .containsExactlyInAnyOrder(State.SUCCESS, State.FAILED, State.CANCELLED, State.TIMEOUT);
    }

    @Test
    void recordsEveryTransitionAndRefusesIllegalOnes() {
        Instant t0 = Instant.parse("2026-11-13T08:00:00Z");
        RunStates run = new RunStates(t0);
        run.move(State.RUNNING, t0.plusSeconds(1), "tasks deployed");
        run.move(State.RUNNING, t0.plusSeconds(2), "again");
        run.move(State.SUCCESS, t0.plusSeconds(3), "success");

        assertThat(run.state()).isEqualTo(State.SUCCESS);
        assertThat(run.transitions())
                .containsExactly(
                        new Transition(null, State.QUEUE, t0, "submitted"),
                        new Transition(State.QUEUE, State.RUNNING, t0.plusSeconds(1), "tasks deployed"),
                        new Transition(State.RUNNING, State.SUCCESS, t0.plusSeconds(3), "success"));
        assertThatThrownBy(() -> run.move(State.RUNNING, t0, "back"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A run cannot go from SUCCESS to RUNNING");
    }
}
