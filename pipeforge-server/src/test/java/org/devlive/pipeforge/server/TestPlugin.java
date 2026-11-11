/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server;

import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.devlive.pipeforge.api.ApiVersion;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkCommitter;
import org.devlive.pipeforge.api.sink.SinkContext;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.ReaderContext;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.api.source.SourceContext;
import org.devlive.pipeforge.api.source.SourceReader;
import org.devlive.pipeforge.api.source.SourceSplit;
import org.devlive.pipeforge.api.source.SplitEnumerator;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;

/**
 * A plugin package for the API tests: plugin.yaml, an empty jar, and factories from the test
 * class path ({@code demo-source} and {@code demo-sink}). A second directory without jars is a
 * package the server rejects.
 */
public final class TestPlugin {

    public enum Mode {
        FAST,
        SAFE
    }

    static final Option<Integer> ROWS =
            Options.key("rows").intType().defaultValue(10).withDescription("Rows to produce");
    static final Option<String> TABLE = Options.key("table").stringType().defaultValue("demo.orders");
    static final Option<String> PASSWORD =
            Options.key("password").stringType().defaultValue("x").withSensitive(true);
    static final Option<Duration> INTERVAL =
            Options.key("interval").durationType().defaultValue(Duration.ofSeconds(30));
    static final Option<Mode> MODE = Options.key("mode").enumType(Mode.class).defaultValue(Mode.FAST);
    static final Option<String> QUERY = Options.key("query").stringType().noDefault();
    static final Option<String> PATH = Options.key("path").stringType().noDefault();
    static final Option<String> KEY = Options.key("key").stringType().noDefault();

    private TestPlugin() {}

    /** Writes the plugin directories under {@code root} and returns the plugin directory to configure. */
    public static Path install(Path root) {
        try {
            Path plugins = Files.createDirectories(root.resolve("plugins"));
            Path lib = Files.createDirectories(plugins.resolve("demo-1.0.0").resolve("lib"));
            try (OutputStream out = Files.newOutputStream(lib.resolve("demo.jar"));
                    JarOutputStream jar = new JarOutputStream(out, new Manifest())) {
                jar.flush();
            }
            Files.writeString(
                    lib.getParent().resolve("plugin.yaml"),
                    """
                    id: demo
                    name: Demo
                    version: 1.0.0
                    api-version: "%s"
                    description: Test components
                    datasource: { type: demo-db, versions: ["1.0"] }
                    provides:
                      - { type: source, identifier: demo-source, class: %s }
                      - { type: sink, identifier: demo-sink, class: %s }
                    """
                            .formatted(
                                    ApiVersion.descriptorForm(), DemoSource.class.getName(), DemoSink.class.getName()));
            Path broken = Files.createDirectories(plugins.resolve("broken-1.0.0"));
            Files.writeString(
                    broken.resolve("plugin.yaml"),
                    """
                    id: broken
                    name: Broken
                    version: 1.0.0
                    api-version: "%s"
                    provides: []
                    """
                            .formatted(ApiVersion.descriptorForm()));
            return plugins;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A bounded source producing {@code table} with columns id and name. */
    public static final class DemoSource implements SourceFactory {
        @Override
        public String identifier() {
            return "demo-source";
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder()
                    .optional(ROWS, TABLE, PASSWORD, INTERVAL, MODE)
                    .exclusive(QUERY, PATH)
                    .conditional(MODE, Mode.SAFE, KEY)
                    .nativeNamespaces("demo.properties")
                    .build();
        }

        @Override
        public Source<?, ?> createSource(FactoryContext context) {
            CatalogTable table = CatalogTable.of(
                            TableId.parse(context.options().get(TABLE)),
                            List.of(Column.of("id", INT64().notNull()), Column.of("name", STRING(20))))
                    .withPrimaryKey("id");
            return new DemoSourceInstance(table);
        }
    }

    /** A sink that accepts everything and has no options of its own. */
    public static final class DemoSink implements SinkFactory {
        @Override
        public String identifier() {
            return "demo-sink";
        }

        @Override
        public OptionRule optionRule() {
            return OptionRule.builder().build();
        }

        @Override
        public Sink<?, ?> createSink(FactoryContext context) {
            return new DemoSinkInstance();
        }
    }

    record DemoSourceInstance(CatalogTable table) implements Source<SourceSplit, Void> {
        @Override
        public Boundedness boundedness() {
            return Boundedness.BOUNDED;
        }

        @Override
        public List<CatalogTable> producedTables(SourceContext context) {
            return List.of(table);
        }

        @Override
        public SplitEnumerator<SourceSplit, Void> createEnumerator(EnumeratorContext<SourceSplit> context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SplitEnumerator<SourceSplit, Void> restoreEnumerator(
                EnumeratorContext<SourceSplit> context, Void state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SourceReader<SourceSplit> createReader(ReaderContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VersionedSerializer<SourceSplit> splitSerializer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VersionedSerializer<Void> enumeratorStateSerializer() {
            throw new UnsupportedOperationException();
        }
    }

    static final class DemoSinkInstance implements Sink<String, Void> {
        @Override
        public void validate(List<CatalogTable> inputs, SinkContext context) {}

        @Override
        public SinkWriter<String, Void> createWriter(WriterContext context, List<Void> restored) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SinkCommitter<String>> createCommitter() {
            return Optional.empty();
        }

        @Override
        public VersionedSerializer<String> committableSerializer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VersionedSerializer<Void> writerStateSerializer() {
            throw new UnsupportedOperationException();
        }
    }
}
