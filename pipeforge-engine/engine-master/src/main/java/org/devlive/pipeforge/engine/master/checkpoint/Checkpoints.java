/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.checkpoint;

import java.util.concurrent.Executor;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;

/**
 * What a master takes checkpoints with: the storage of {@code checkpoint.dir}, the metastore it
 * records them in, and a thread for slow storage work such as deleting old checkpoints.
 */
public record Checkpoints(CheckpointStorage storage, CheckpointStore store, Executor io) {}
