/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.testkit.connector.Datasets;
import org.devlive.pipeforge.testkit.data.TestTable;

/**
 * Connectors that break the contract on purpose. They are nested so that Surefire does not run
 * them; {@link ContractFailuresTest} runs them with the JUnit engine test kit and checks the
 * failures.
 */
final class BrokenConnectors {

    private BrokenConnectors() {}

    /** Reads one row less than prepared. */
    static class DroppingSource extends SourceContract {
        @Override
        protected SourceUnderTest source() {
            return new RowsSourceContractTest.RowsSource() {
                @Override
                public Map<String, Object> options(TestTable table) {
                    List<Row> rows = new ArrayList<>(table.rows());
                    if (!rows.isEmpty()) {
                        rows.remove(rows.size() - 1);
                    }
                    return Map.of("dataset", Datasets.input(table.withRows(table.name(), rows)));
                }

                @Override
                public Set<TypeRoot> supportedTypes() {
                    return Set.of(TypeRoot.INT32, TypeRoot.STRING);
                }

                @Override
                public int largeRowCount() {
                    return 100;
                }
            };
        }
    }

    /** Forgets on a restore which splits it had not assigned yet, and assigns them all again. */
    static class ForgetfulSource extends SourceContract {
        @Override
        protected SourceUnderTest source() {
            return new RowsSourceContractTest.RowsSource() {
                @Override
                public org.devlive.pipeforge.api.factory.SourceFactory factory() {
                    return new ForgetfulFactory(super.factory());
                }

                @Override
                public Set<TypeRoot> supportedTypes() {
                    return Set.of(TypeRoot.INT32);
                }

                @Override
                public int largeRowCount() {
                    return 100;
                }
            };
        }
    }

    record ForgetfulFactory(org.devlive.pipeforge.api.factory.SourceFactory delegate)
            implements org.devlive.pipeforge.api.factory.SourceFactory {
        @Override
        public String identifier() {
            return "testkit-forgetful";
        }

        @Override
        public org.devlive.pipeforge.api.option.OptionRule optionRule() {
            return delegate.optionRule();
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public org.devlive.pipeforge.api.source.Source<?, ?> createSource(
                org.devlive.pipeforge.api.factory.FactoryContext context) throws Exception {
            org.devlive.pipeforge.api.source.Source source = delegate.createSource(context);
            return new org.devlive.pipeforge.api.source.Source() {
                @Override
                public org.devlive.pipeforge.api.source.Boundedness boundedness() {
                    return source.boundedness();
                }

                @Override
                public List producedTables(org.devlive.pipeforge.api.source.SourceContext sourceContext) {
                    return source.producedTables(sourceContext);
                }

                @Override
                public org.devlive.pipeforge.api.source.SplitEnumerator createEnumerator(
                        org.devlive.pipeforge.api.source.EnumeratorContext enumeratorContext) {
                    return source.createEnumerator(enumeratorContext);
                }

                @Override
                public org.devlive.pipeforge.api.source.SplitEnumerator restoreEnumerator(
                        org.devlive.pipeforge.api.source.EnumeratorContext enumeratorContext, Object state) {
                    // the state is ignored: every split is assigned again
                    return source.createEnumerator(enumeratorContext);
                }

                @Override
                public org.devlive.pipeforge.api.source.SourceReader createReader(
                        org.devlive.pipeforge.api.source.ReaderContext readerContext) {
                    return source.createReader(readerContext);
                }

                @Override
                public org.devlive.pipeforge.api.serialization.VersionedSerializer splitSerializer() {
                    return source.splitSerializer();
                }

                @Override
                public org.devlive.pipeforge.api.serialization.VersionedSerializer enumeratorStateSerializer() {
                    return source.enumeratorStateSerializer();
                }
            };
        }
    }

    /** Does not give a required option, so nothing plans. */
    static class MissingOptionSource extends SourceContract {
        @Override
        protected SourceUnderTest source() {
            return new RowsSourceContractTest.RowsSource() {
                @Override
                public Map<String, Object> options(TestTable table) {
                    return Map.of("splits", 2);
                }
            };
        }

        @Override
        protected Map<String, String> skippedGroups(SourceUnderTest source) {
            return Map.of("types", "only options matter here", "batch", "only options matter here");
        }
    }

    /** Reads back every row twice. */
    static class DuplicatingSink extends SinkContract {
        @Override
        protected SinkUnderTest sink() {
            return new CollectSinkContractTest.CollectSink() {
                @Override
                public Optional<List<Row>> readBack(TestTable table, Map<String, Object> options) {
                    List<Row> rows =
                            new ArrayList<>(super.readBack(table, options).orElseThrow());
                    rows.addAll(new ArrayList<>(rows));
                    return Optional.of(rows);
                }

                @Override
                public int largeRowCount() {
                    return 100;
                }
            };
        }

        @Override
        protected Map<String, String> skippedGroups(SinkUnderTest sink) {
            return Map.of("options", "not under test", "no-such-group", "a typo");
        }
    }

    /** Loses the row with id 0, which its model does not say. */
    static class DroppingTransform extends TransformContract {
        @Override
        protected TransformUnderTest transform() {
            return new UpperTransformContractTest.UpperName() {
                @Override
                public Map<String, Object> options() {
                    return Map.of("column", "name", "drop-first", true);
                }

                @Override
                public Optional<Map<String, Object>> failingOptions() {
                    return Optional.empty();
                }

                @Override
                public boolean supportsMultipleTables() {
                    return false;
                }
            };
        }

        @Override
        protected Map<String, String> skippedGroups(TransformUnderTest transform) {
            return Map.of("options", "not under test");
        }
    }
}
