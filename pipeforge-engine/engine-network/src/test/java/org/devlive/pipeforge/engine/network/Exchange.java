/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.devlive.pipeforge.api.type.DataTypes.FIELD;
import static org.devlive.pipeforge.api.type.DataTypes.INT64;
import static org.devlive.pipeforge.api.type.DataTypes.STRING;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.devlive.pipeforge.engine.network.gate.InputGate;
import org.devlive.pipeforge.engine.network.partition.ResultPartition;
import org.devlive.pipeforge.engine.network.row.RowCodec;
import org.devlive.pipeforge.engine.network.row.TableLayout;

/** Rows, producers and consumers for the exchange tests. */
final class Exchange {

    static final TableId TABLE = TableId.of("orders");
    static final ExecutorService THREADS = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "exchange-test");
        thread.setDaemon(true);
        return thread;
    });

    private Exchange() {}

    static RowCodec codec() {
        return new RowCodec(List.of(new TableLayout(0, TABLE, List.of(FIELD("id", INT64()), FIELD("name", STRING())))));
    }

    static Row row(long id) {
        return GenericRow.of(TABLE, id, "order-" + id);
    }

    static ResultPartitionId partition(String vertex) {
        return new ResultPartitionId("run-1", vertex, 0, 0, 0);
    }

    /** Small buffers, so that a few thousand rows fill many of them. */
    static NetworkOptions options() {
        return NetworkOptions.defaults()
                .withMemory(8L << 20, 4096)
                .withMaxRowBytes(64 << 10)
                .withTimeouts(Duration.ofMillis(20), Duration.ofSeconds(30), Duration.ofSeconds(2));
    }

    /** Writes rows {@code from} to {@code from + count - 1} round robin over the subpartitions, then ends. */
    static CompletableFuture<Void> produce(ResultPartition output, long from, long count, boolean finish) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        for (long id = from; id < from + count; id++) {
                            output.emit((int) (id % output.subpartitions()), row(id), null, () -> false);
                        }
                        if (finish) {
                            output.finish();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                },
                THREADS);
    }

    /** What a consumer read: the row ids per channel and the events in order. */
    record Received(List<List<Long>> ids, List<Object> stream) {
        long count() {
            return ids.stream().mapToLong(List::size).sum();
        }
    }

    /** Reads the input to its end, recycling every buffer. */
    static Received drain(InputGate gate, RowCodec codec, Duration pause) throws InterruptedException {
        List<List<Long>> ids = new ArrayList<>();
        for (int i = 0; i < gate.channels(); i++) {
            ids.add(new ArrayList<>());
        }
        List<Object> stream = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!gate.isFinished()) {
            assertThat(System.nanoTime()).as("the input ends").isLessThan(deadline);
            InputGate.Next next = gate.next(Duration.ofMillis(100));
            if (next == null) {
                continue;
            }
            Buffer buffer = next.buffer();
            try {
                if (buffer.isEvent()) {
                    stream.add(NetworkEvent.fromBuffer(buffer));
                } else {
                    for (RowCodec.Decoded row : codec.decodeAll(buffer.content())) {
                        long id = (Long) row.row().get(0);
                        ids.get(next.channel()).add(id);
                        stream.add(id);
                    }
                }
            } finally {
                buffer.recycle();
            }
            if (!pause.isZero()) {
                Thread.sleep(pause.toMillis());
            }
        }
        return new Received(ids, stream);
    }

    /** Ids {@code from}, {@code from + step}, … below {@code to}. */
    static List<Long> every(long from, long step, long to) {
        List<Long> ids = new ArrayList<>();
        for (long id = from; id < to; id += step) {
            ids.add(id);
        }
        return ids;
    }

    static void await(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(5);
        }
    }
}
