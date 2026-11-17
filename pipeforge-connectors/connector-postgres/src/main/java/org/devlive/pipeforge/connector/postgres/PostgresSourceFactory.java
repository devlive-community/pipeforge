/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.postgres;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.connector.jdbc.JdbcOptions;
import org.devlive.pipeforge.connector.jdbc.JdbcSource;
import org.devlive.pipeforge.connector.jdbc.JdbcSourceConfig;

/**
 * Creates the PostgreSQL source: {@code type: jdbc-postgres}. It reads one table, the tables {@code
 * tables.include} selects, or a query, with the options of the JDBC base and the PostgreSQL dialect.
 */
public final class PostgresSourceFactory implements SourceFactory {

    static final PostgresDialect DIALECT = new PostgresDialect();

    @Override
    public String identifier() {
        return "jdbc-postgres";
    }

    @Override
    public OptionRule optionRule() {
        return JdbcOptions.sourceRule().build();
    }

    @Override
    public JdbcSource createSource(FactoryContext context) {
        return new JdbcSource(JdbcSourceConfig.of(context.options(), DIALECT, context.nodeName()), DIALECT);
    }
}
