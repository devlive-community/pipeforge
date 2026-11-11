/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;

/**
 * The testkit source with an option rule that uses every feature the {@code options} group checks:
 * choices with an old name, a sensitive option, exclusive options and a conditional option. Lives
 * in the test classes, so the test {@code plugin.yaml} applies to it.
 */
public final class FixtureRowsFactory implements SourceFactory {

    enum Mode {
        ALL,
        FILTERED
    }

    static final Option<Mode> MODE =
            Options.key("mode").enumType(Mode.class).defaultValue(Mode.ALL).withDeprecatedKeys("read-mode");
    static final Option<String> FILTER = Options.key("filter").stringType().noDefault();
    static final Option<String> PASSWORD =
            Options.key("password").stringType().noDefault().withSensitive(true);
    static final Option<String> TABLE = Options.key("table").stringType().noDefault();
    static final Option<String> QUERY = Options.key("query").stringType().noDefault();
    static final Option<Double> RATIO =
            Options.key("ratio").doubleType().defaultValue(1.0).withRange(0.0, 1.0);
    static final Option<Long> LIMIT =
            Options.key("limit").longType().defaultValue(10L).withRange(1, null);

    @Override
    public String identifier() {
        return "fixture-rows";
    }

    @Override
    public OptionRule optionRule() {
        return OptionRule.builder()
                .required(TestkitConnectors.DATASET)
                .optional(TestkitConnectors.SPLITS, MODE, FILTER, PASSWORD, RATIO, LIMIT)
                .exclusive(TABLE, QUERY)
                .conditional(MODE, Mode.FILTERED, FILTER)
                .build();
    }

    @Override
    public Source<?, ?> createSource(FactoryContext context) throws Exception {
        return TestkitConnectors.rows().createSource(context);
    }
}
