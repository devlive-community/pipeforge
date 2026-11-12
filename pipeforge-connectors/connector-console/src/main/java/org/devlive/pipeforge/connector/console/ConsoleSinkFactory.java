/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.option.OptionRule;

/** The console sink ({@code type: console}): prints rows as tables or JSON lines. */
public final class ConsoleSinkFactory implements SinkFactory {

    @Override
    public String identifier() {
        return "console";
    }

    @Override
    public OptionRule optionRule() {
        return OptionRule.builder()
                .optional(
                        ConsoleOptions.FORMAT,
                        ConsoleOptions.TARGET,
                        ConsoleOptions.LIMIT,
                        ConsoleOptions.PRINT_KIND,
                        ConsoleOptions.BATCH_SIZE,
                        ConsoleOptions.MAX_WIDTH)
                .build();
    }

    @Override
    public ConsoleSink createSink(FactoryContext context) {
        return new ConsoleSink(ConsoleSettings.read(context.options()), context.inputTables());
    }
}
