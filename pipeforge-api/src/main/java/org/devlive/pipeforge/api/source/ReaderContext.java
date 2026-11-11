/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.factory.JobContext;

/** What a reader can do: identify itself, ask for splits and talk to its enumerator. */
@Stable
public interface ReaderContext {

    JobContext job();

    /** Index of this reader among {@link #parallelism()} readers, from 0. */
    int subtaskIndex();

    int parallelism();

    /** Asks the enumerator for another split (pull mode: fast readers get more work). */
    void sendSplitRequest();

    void sendReaderEvent(ReaderEvent event);

    /** The class loader of the plugin. */
    ClassLoader classLoader();
}
