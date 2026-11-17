/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc.generic;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.connector.jdbc.GenericDialect;
import org.devlive.pipeforge.connector.jdbc.JdbcOptions;
import org.devlive.pipeforge.connector.jdbc.JdbcSource;
import org.devlive.pipeforge.connector.jdbc.JdbcSourceConfig;

/**
 * Creates the generic JDBC source: {@code type: jdbc}. It reads one table, the tables {@code
 * tables.include} selects, or a query, with ANSI SQL and the standard JDBC types. The driver is
 * the installed driver plugin that accepts the URL, or the class the {@code driver} option names.
 */
public final class JdbcGenericSourceFactory implements SourceFactory {

    static final GenericDialect DIALECT = new GenericDialect();

    @Override
    public String identifier() {
        return "jdbc";
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
