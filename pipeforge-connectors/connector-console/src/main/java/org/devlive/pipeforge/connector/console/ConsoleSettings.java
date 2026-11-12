/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import java.io.Serializable;
import org.devlive.pipeforge.api.option.ReadonlyConfig;

/**
 * The validated options of a console sink node.
 *
 * @param limit rows each writer prints at most, or -1 for all
 */
record ConsoleSettings(
        ConsoleOptions.Format format,
        ConsoleOptions.Target target,
        long limit,
        boolean printKind,
        int batchSize,
        int maxWidth)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    static ConsoleSettings read(ReadonlyConfig options) {
        Long limit = options.getOptional(ConsoleOptions.LIMIT).orElse(null);
        return new ConsoleSettings(
                options.get(ConsoleOptions.FORMAT),
                options.get(ConsoleOptions.TARGET),
                limit == null ? -1 : limit,
                options.get(ConsoleOptions.PRINT_KIND),
                options.get(ConsoleOptions.BATCH_SIZE),
                options.get(ConsoleOptions.MAX_WIDTH));
    }
}
