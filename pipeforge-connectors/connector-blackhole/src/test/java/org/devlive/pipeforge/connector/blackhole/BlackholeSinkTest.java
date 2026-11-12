/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.blackhole;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.sink.SinkWriter;
import org.devlive.pipeforge.api.table.DropTable;
import org.devlive.pipeforge.connector.fake.FakeSourceFactory;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.testkit.TestJob;
import org.devlive.pipeforge.testkit.TestRun;
import org.devlive.pipeforge.testkit.TestkitEngine;
import org.devlive.pipeforge.testkit.TestkitExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestkitExtension.class)
class BlackholeSinkTest {

    @Test
    void discardsRowsAndSchemaChanges() throws Exception {
        SinkWriter<Void, Void> writer = new BlackholeWriter(Throttle.NONE);
        writer.write(GenericRow.of(TableId.of("t"), 1L));
        writer.applySchemaChange(new DropTable(TableId.of("t")));
        writer.flush();
        writer.close();
        assertThat(writer.snapshotState(1)).isEmpty();
        assertThat(writer.prepareCommit(1)).isEmpty();
    }

    @Test
    void theSinkSerializesWithItsRate() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new BlackholeSink(250));
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(((BlackholeSink) in.readObject()).rowsPerSecond()).isEqualTo(250);
        }
        assertThat(BlackholeSink.NothingSerializer.INSTANCE.version()).isEqualTo(1);
        assertThat(BlackholeSink.NothingSerializer.INSTANCE.serialize(null)).isEmpty();
        assertThat(BlackholeSink.NothingSerializer.INSTANCE.deserialize(1, new byte[0]))
                .isNull();
    }

    @Test
    void theRateIsSharedByTheParallelInstances(TestkitEngine engine) {
        // 200 rows at 400 rows per second take half a second whatever the parallelism; the
        // throttles may run a tenth of a second ahead, so the job takes at least 0.4 seconds.
        long started = System.nanoTime();
        TestRun run = engine.run(TestJob.named("throttled")
                        .source(new FakeSourceFactory(), Map.of("rows", 200, "splits", 2))
                        .sink(new BlackholeSinkFactory(), Map.of("rows-per-second", 400))
                        .parallelism(2))
                .requireSuccess()
                .requireReconciled();
        double seconds = (System.nanoTime() - started) / 1e9;
        assertThat(seconds).isGreaterThanOrEqualTo(0.4);
        JobMetrics metrics = run.metrics();
        assertThat(metrics.rowsWritten()).isEqualTo(200);
    }

    @Test
    void theRateIsOptional() {
        assertThat(new BlackholeSinkFactory().optionRule().optional()).isNotEmpty();
        assertThat(List.of(new BlackholeSinkFactory().identifier())).containsExactly("blackhole");
    }
}
