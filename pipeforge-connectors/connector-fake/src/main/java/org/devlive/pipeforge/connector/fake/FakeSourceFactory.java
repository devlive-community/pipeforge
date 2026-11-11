/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.OptionRule;

/** Creates the fake source: {@code type: fake}. */
public final class FakeSourceFactory implements SourceFactory {

    @Override
    public String identifier() {
        return "fake";
    }

    @Override
    public OptionRule optionRule() {
        return OptionRule.builder()
                .optional(
                        FakeOptions.TABLE,
                        FakeOptions.TABLES,
                        FakeOptions.COLUMNS,
                        FakeOptions.PRIMARY_KEY,
                        FakeOptions.ROWS,
                        FakeOptions.DATA,
                        FakeOptions.SEED,
                        FakeOptions.NULL_RATIO,
                        FakeOptions.BAD_ROW_RATIO,
                        FakeOptions.SPLITS,
                        FakeOptions.ROWS_PER_SECOND,
                        FakeOptions.UNBOUNDED,
                        FakeOptions.EVENT_TIME_COLUMN,
                        FakeOptions.START_TIME,
                        FakeOptions.EVENT_TIME_STEP,
                        FakeOptions.MAX_OUT_OF_ORDER)
                .build();
    }

    @Override
    public FakeSource createSource(FactoryContext context) {
        return new FakeSource(FakeConfig.read(context.options(), context.nodeName()));
    }
}
