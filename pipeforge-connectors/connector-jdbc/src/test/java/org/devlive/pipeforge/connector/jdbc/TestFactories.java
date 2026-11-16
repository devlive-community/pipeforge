/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.OptionRule;

/** A source and a sink over the base with a dialect, as a plugin wires them; {@code jdbc-test}. */
final class TestFactories {

    private TestFactories() {}

    static final class Source implements SourceFactory {
        private final JdbcDialect dialect;

        Source() {
            this(H2.DIALECT);
        }

        Source(JdbcDialect dialect) {
            this.dialect = dialect;
        }

        @Override
        public String identifier() {
            return "jdbc-test";
        }

        @Override
        public OptionRule optionRule() {
            return JdbcOptions.sourceRule().build();
        }

        @Override
        public JdbcSource createSource(FactoryContext context) {
            return new JdbcSource(JdbcSourceConfig.of(context.options(), dialect, context.nodeName()), dialect);
        }
    }

    static final class Sink implements SinkFactory {
        private final JdbcDialect dialect;

        Sink() {
            this(H2.DIALECT);
        }

        Sink(JdbcDialect dialect) {
            this.dialect = dialect;
        }

        @Override
        public String identifier() {
            return "jdbc-test";
        }

        @Override
        public OptionRule optionRule() {
            return JdbcOptions.sinkRule().build();
        }

        @Override
        public JdbcSink createSink(FactoryContext context) {
            return new JdbcSink(
                    JdbcSinkConfig.of(context.options(), dialect, context.nodeName()), dialect, context.inputTables());
        }
    }
}
