/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.network.NetworkEvent;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.partition.ResultPartition;
import org.devlive.pipeforge.engine.network.row.RowCodec;
import org.devlive.pipeforge.engine.network.row.TableLayout;
import org.devlive.pipeforge.engine.rpc.ChainDescriptor;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.jspecify.annotations.Nullable;

/**
 * Where the last node of a chain that has no sink sends its rows: one output per edge, each row
 * to the subpartitions its partitioner chooses. Writing waits while the consumers have no room.
 */
final class NetworkOutput implements ChainOutput {

    private record Edge(ResultPartition partition, Partitioner partitioner) {}

    private final List<Edge> edges = new ArrayList<>();
    private final String node;
    private final BooleanSupplier cancelled;
    private final OperatorClock clock;
    private final int slot;

    /**
     * Creates the outputs of the task.
     *
     * @param tables the tables the last node writes; both ends of an edge number them in this order
     */
    NetworkOutput(
            NetworkEnvironment network,
            ExecutionAttemptId task,
            ChainDescriptor chain,
            List<CatalogTable> tables,
            BooleanSupplier cancelled,
            OperatorClock clock,
            int slot) {
        this.node = chain.tail().id();
        this.cancelled = cancelled;
        this.clock = clock;
        this.slot = slot;
        try {
            for (ChainDescriptor.Output output : chain.outputs()) {
                ResultPartitionId id = new ResultPartitionId(
                        task.runId(), task.vertexId(), task.subtask(), task.attempt(), output.index());
                ResultPartition partition = network.createPartition(id, output.subpartitions(), codec(tables));
                edges.add(new Edge(
                        partition,
                        Partitioner.of(output.partitioning(), output.subpartitions(), task.subtask(), tables)));
            }
        } catch (RuntimeException e) {
            release();
            throw e;
        }
    }

    /** The codec of the rows of {@code tables}: table i has number i. */
    static RowCodec codec(List<CatalogTable> tables) {
        List<TableLayout> layouts = new ArrayList<>(tables.size());
        for (int i = 0; i < tables.size(); i++) {
            CatalogTable table = tables.get(i);
            layouts.add(new TableLayout(
                    i,
                    table.id(),
                    table.columns().stream()
                            .map(c -> new DataType.Field(c.name(), c.type()))
                            .toList()));
        }
        return new RowCodec(layouts);
    }

    @Override
    public void row(Row row) {
        int token = clock.enter(slot);
        try {
            for (Edge edge : edges) {
                edge.partitioner().route(row, subpartition -> emit(edge.partition(), subpartition, row));
            }
        } finally {
            clock.exit(token);
        }
    }

    private void emit(ResultPartition partition, int subpartition, Row row) {
        try {
            partition.emit(subpartition, row, null, cancelled::getAsBoolean);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledWrite(e);
        }
    }

    @Override
    public void schemaChange(SchemaChangeEvent event) {
        throw PipeForgeException.of(ErrorCode.TOPOLOGY_UNSUPPORTED)
                .param("node", node)
                .param(
                        "reason",
                        "it sends a schema change of " + event.table()
                                + " to another task; schema changes cannot cross a data exchange in this version")
                .scope(ErrorScope.node(node))
                .build();
    }

    /** Ends every output after the rows written: the consumers read them to the end. */
    void finish() {
        for (Edge edge : edges) {
            edge.partition().broadcastEvent(NetworkEvent.END_OF_INPUT_EVENT);
            edge.partition().finish();
        }
    }

    /** Drops the rows of outputs that did not finish: the run failed or was cancelled. */
    void release() {
        for (Edge edge : edges) {
            if (!edge.partition().isFinished()) {
                edge.partition().release();
            }
        }
    }

    /** Bytes of rows written to the outputs. */
    long bytesSent() {
        long total = 0;
        for (Edge edge : edges) {
            total += edge.partition().metrics().bytes();
        }
        return total;
    }

    /** Nanoseconds the task waited for network buffers: its backpressure. */
    long backpressureNanos() {
        long total = 0;
        for (Edge edge : edges) {
            total += edge.partition().metrics().backpressuredMs() * 1_000_000;
        }
        return total;
    }

    /** A write stopped because the task was cancelled. */
    static final class CancelledWrite extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CancelledWrite(@Nullable Throwable cause) {
            super("cancelled while waiting for a network buffer", cause);
        }
    }
}
