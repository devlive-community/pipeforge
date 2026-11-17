/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.postgres;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.connector.jdbc.JdbcOptions;
import org.devlive.pipeforge.connector.jdbc.JdbcSink;
import org.devlive.pipeforge.connector.jdbc.JdbcSinkConfig;

/**
 * Creates the PostgreSQL sink: {@code type: jdbc-postgres}. It inserts the rows of its input into an
 * existing table in batches, which the driver rewrites into multi-row inserts.
 */
public final class PostgresSinkFactory implements SinkFactory {

    static final PostgresDialect DIALECT = new PostgresDialect();

    @Override
    public String identifier() {
        return "jdbc-postgres";
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
