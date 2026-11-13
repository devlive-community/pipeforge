/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.Map;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.TaskState;

/** The tasks of a worker, as its connection to the master reports and fences them. */
public interface LocalTasks {

    /** Every task that has not reported a final state yet, with its state. */
    Map<ExecutionAttemptId, TaskState> taskStates();

    /** Cancels every task: the worker lost its master, and only the current master controls tasks. */
    void cancelAll();
}
