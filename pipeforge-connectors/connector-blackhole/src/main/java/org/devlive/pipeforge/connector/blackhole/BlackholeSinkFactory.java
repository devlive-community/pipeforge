/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.blackhole;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;

/** The blackhole sink ({@code type: blackhole}): discards rows. */
public final class BlackholeSinkFactory implements SinkFactory {

    static final Option<Double> ROWS_PER_SECOND = Options.key("rows-per-second")
            .doubleType()
            .defaultValue(0.0)
            .withRange(0.0, null)
            .withDescription("Maximum rows per second of the whole sink, shared equally by its parallel instances;"
                    + " a write waits when it would go faster, as a slow target does. 0 for no limit.");

    @Override
    public String identifier() {
        return "blackhole";
    }

    @Override
    public OptionRule optionRule() {
        return OptionRule.builder().optional(ROWS_PER_SECOND).build();
    }

    @Override
    public BlackholeSink createSink(FactoryContext context) {
        return new BlackholeSink(context.options().get(ROWS_PER_SECOND));
    }
}
