/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc.generic;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.connector.jdbc.GenericDialect;
import org.devlive.pipeforge.connector.jdbc.JdbcOptions;
import org.devlive.pipeforge.connector.jdbc.JdbcSink;
import org.devlive.pipeforge.connector.jdbc.JdbcSinkConfig;

/**
 * Creates the generic JDBC sink: {@code type: jdbc}. It inserts the rows of its input into an
 * existing table in batches, with ANSI SQL; the driver comes from an installed driver plugin.
 */
public final class JdbcGenericSinkFactory implements SinkFactory {

    static final GenericDialect DIALECT = new GenericDialect();

    @Override
    public String identifier() {
        return "jdbc";
    }

    @Override
    public OptionRule optionRule() {
        return JdbcOptions.sinkRule().build();
    }

    @Override
    public JdbcSink createSink(FactoryContext context) {
        return new JdbcSink(
                JdbcSinkConfig.of(context.options(), DIALECT, context.nodeName()), DIALECT, context.inputTables());
    }
}
