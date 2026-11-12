/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.sink.WriterContext;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.Column;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.testkit.data.TestData;
import org.junit.jupiter.api.Test;

class ConsoleSinkTest {

    private static ConsoleSink copy(ConsoleSink sink) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(sink);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (ConsoleSink) in.readObject();
        }
    }

    @Test
    void theInputTablesSurviveSerialization() throws Exception {
        List<Column> columns = new ArrayList<>();
        int i = 0;
        for (DataType type : TestData.TYPES) {
            columns.add(Column.of("c" + i++, type));
        }
        columns.add(Column.of("needs quotes", TestData.TYPES.get(0).notNull()));
        CatalogTable table = CatalogTable.of(new TableId("cat", "db", "public", "t"), columns);
        ConsoleSink sink = new ConsoleSink(
                new ConsoleSettings(ConsoleOptions.Format.JSON, ConsoleOptions.Target.STDOUT, -1, true, 20, 40),
                List.of(table));

        ConsoleSink.Schema schema = ConsoleSink.Schema.of(table);
        assertThat(schema.toTable().id()).isEqualTo(table.id());
        assertThat(schema.toTable().columns()).isEqualTo(table.columns());

        ConsoleSink copy = copy(sink);
        assertThat(copy.settings()).isEqualTo(sink.settings());
        assertThat(ConsoleSink.NothingSerializer.INSTANCE.version()).isEqualTo(1);
        assertThat(ConsoleSink.NothingSerializer.INSTANCE.serialize(null)).isEmpty();
        assertThat(ConsoleSink.NothingSerializer.INSTANCE.deserialize(1, new byte[0]))
                .isNull();
    }

    @Test
    void writersPrintToTheChosenStream() throws Exception {
        CatalogTable table = CatalogTable.of(TableId.of("t"), List.of(Column.of("id", TestData.TYPES.get(4))));
        WriterContext context = new WriterContext() {
            @Override
            public JobContext job() {
                return new JobContext("job", 1, ZoneId.of("UTC"));
            }

            @Override
            public int subtaskIndex() {
                return 0;
            }

            @Override
            public int parallelism() {
                return 1;
            }

            @Override
            public ClassLoader classLoader() {
                return getClass().getClassLoader();
            }
        };
        try (CapturedOutput output = new CapturedOutput()) {
            ConsoleSink sink = copy(new ConsoleSink(
                    new ConsoleSettings(ConsoleOptions.Format.JSON, ConsoleOptions.Target.STDERR, -1, false, 20, 40),
                    List.of(table)));
            try (SinkWriter<Void, Void> writer = sink.createWriter(context, List.of())) {
                writer.write(GenericRow.of(TableId.of("t"), 7L));
            }
            assertThat(output.text()).isEqualTo("{\"table\":\"t\",\"row\":{\"id\":7}}\n");
        }
        assertThat(ConsoleOutput.stream(ConsoleOptions.Target.STDERR)).isSameAs(System.err);
        assertThat(ConsoleOutput.stream(ConsoleOptions.Target.STDOUT)).isSameAs(System.out);
    }
}
