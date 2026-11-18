/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A run with its definition and the changes of its state.
 *
 * @param run the run
 * @param definition the job definition it ran
 * @param transitions its changes of state, oldest first
 */
public record RunDetail(Run run, @Nullable String definition, List<Transition> transitions) {

    public RunDetail {
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }
}
