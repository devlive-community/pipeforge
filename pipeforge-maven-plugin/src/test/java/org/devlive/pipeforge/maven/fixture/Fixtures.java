/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven.fixture;

import java.time.Duration;
import java.util.List;
import org.devlive.pipeforge.api.capability.SupportsLimitPushDown;
import org.devlive.pipeforge.api.capability.SupportsRowCount;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.api.source.SourceContext;
import org.devlive.pipeforge.api.source.SourceReader;
import org.devlive.pipeforge.api.source.SourceSplit;
import org.devlive.pipeforge.api.source.SplitEnumerator;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.transform.Collector;
import org.devlive.pipeforge.api.transform.Transform;
import org.devlive.pipeforge.api.transform.TransformContext;

/** Factories used by the inspector and docs tests. */
public final class Fixtures {

    private Fixtures() {}

    public enum Mode {
        FAST,
        SAFE
    }

    public static final Option<String> TABLE =
            Options.key("table").stringType().noDefault().withDescription("Table");
    public static final Option<String> QUERY = Options.key("query").stringType().noDefault();
    public static final Option<Integer> ROWS = Options.key("rows")
            .intType()
            .defaultValue(10)
            .withRange(1, 1000)
            .withGroup("data")
            .withSince("0.1.0");
    public static final Option<Duration> INTERVAL =
            Options.key("interval").durationType().defaultValue(Duration.ofSeconds(30));
    public static final Option<Mode> MODE =
            Options.key("mode").enumType(Mode.class).defaultValue(Mode.FAST);
    public static final Option<String> KEY = Options.key("key").stringType().noDefault();
    public static final Option<String> PASSWORD =
            Options.key("password").stringType().defaultValue("secret").withSensitive(true);

    /** A source with capabilities. */
    public abstract static class CapableSource
            implements Source<SourceSplit, Void>, SupportsLimitPushDown, SupportsRowCount {
        private static final long serialVersionUID = 1L;
    }

    public static final class GoodSourceFactory implements SourceFactory {
        @Override
        public String identifier() {
            return "good";
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder()
                    .required(ROWS)
                    .optional(INTERVAL, PASSWORD)
                    .exclusive(TABLE, QUERY)
                    .conditional(MODE, Mode.SAFE, KEY)
                    .nativeNamespaces("good.client")
                    .build();
        }

        @Override
        public CapableSource createSource(FactoryContext context) {
            throw new UnsupportedOperationException();
        }
    }

    public static final class WrongIdFactory implements TransformFactory {
        @Override
        public String identifier() {
            return "something-else";
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder().build();
        }

        @Override
        public Transform createTransform(FactoryContext context) {
            return new Transform() {
                private static final long serialVersionUID = 1L;

                @Override
                public List<CatalogTable> transformSchema(List<CatalogTable> inputs) {
                    return inputs;
                }

                @Override
                public void open(TransformContext context) {}

                @Override
                public void process(Row row, Collector<Row> out) {
                    out.collect(row);
                }
            };
        }
    }

    public static final class BrokenFactory implements SourceFactory {
        public BrokenFactory() {
            throw new IllegalStateException("cannot start");
        }

        @Override
        public String identifier() {
            return "broken";
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder().build();
        }

        @Override
        public Source<?, ?> createSource(FactoryContext context) {
            throw new UnsupportedOperationException();
        }
    }

    public static final class FailingRuleFactory implements SourceFactory {
        @Override
        public String identifier() {
            return "failing";
        }

        @Override
        public OptionRule optionRule() {
            throw new IllegalArgumentException("bad rule");
        }

        @Override
        public Source<?, ?> createSource(FactoryContext context) {
            throw new UnsupportedOperationException();
        }
    }

    abstract static class HiddenFactory implements SourceFactory {}

    /** Not a factory at all. */
    public static final class NotAFactory {}

    /** Unused helpers that keep the abstract types referenced. */
    static Class<?>[] types() {
        return new Class<?>[] {
            HiddenFactory.class, SplitEnumerator.class, SourceReader.class, EnumeratorContext.class,
            ReaderContext.class, SourceContext.class, Boundedness.class, VersionedSerializer.class
        };
    }
}
