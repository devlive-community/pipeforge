/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.List;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.table.SchemaChangeEvent;
import org.devlive.pipeforge.api.transform.Collector;
import org.devlive.pipeforge.api.transform.Transform;
import org.devlive.pipeforge.api.transform.TransformContext;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.jspecify.annotations.Nullable;

/** A transform in the chain; it calls the next operator for every row it emits, on the same thread. */
final class TransformOperator implements ChainOutput {

    private final PlannedNode node;
    private final OperatorCall call;
    private final ChainOutput next;
    private final Collector<Row> collector;
    private final OperatorMetrics metrics;
    private final OperatorClock clock;
    private final int slot;
    private long emitted;
    private @Nullable Transform transform;

    TransformOperator(PlannedNode node, OperatorCall call, ChainOutput next, OperatorClock clock, int slot) {
        this.node = node;
        this.call = call;
        this.next = next;
        this.clock = clock;
        this.slot = slot;
        this.metrics = new OperatorMetrics(node.id());
        this.collector = row -> {
            emitted++;
            next.row(row);
        };
    }

    OperatorMetrics metrics() {
        return metrics;
    }

    long selfNanos() {
        return clock.selfNanos(slot);
    }

    void open(JobContext job, int subtask, int parallelism, ExpressionCompiler expressions) {
        TransformFactory factory = (TransformFactory) node.component().factory();
        Transform created = call.call(
                "create the transform", () -> factory.createTransform(NodeContext.of(node, job, expressions)));
        transform = created;
        TransformContext context = new TaskTransformContext(
                job, subtask, parallelism, node.inputTables(), call.classLoader(), expressions);
        call.run("open the transform", () -> created.open(context));
    }

    @Override
    public void row(Row row) {
        Transform current = opened();
        metrics.rowsIn.increment();
        emitted = 0;
        int token = clock.enter(slot);
        try {
            call.run("process a row", () -> current.process(row, collector));
        } finally {
            clock.exit(token);
        }
        if (emitted == 0) {
            metrics.rowsFiltered.increment();
        } else {
            metrics.rowsOut.add(emitted);
            if (emitted > 1) {
                metrics.rowsExpanded.add(emitted - 1);
            }
        }
    }

    @Override
    public void schemaChange(SchemaChangeEvent event) {
        Transform current = opened();
        SchemaChangeEvent mapped = call.call("map a schema change", () -> current.transformSchemaChange(event));
        if (mapped != null) {
            next.schemaChange(mapped);
        }
    }

    void close() {
        Transform current = transform;
        if (current != null) {
            transform = null;
            call.run("close the transform", current::close);
        }
    }

    private Transform opened() {
        Transform current = transform;
        if (current == null) {
            throw new IllegalStateException("The transform " + node.id() + " is not open");
        }
        return current;
    }

    private record TaskTransformContext(
            JobContext job,
            int subtaskIndex,
            int parallelism,
            List<CatalogTable> inputTables,
            ClassLoader classLoader,
            ExpressionCompiler expressions)
            implements TransformContext {}
}
