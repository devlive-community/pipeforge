/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.preview;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.row.TableId;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorInfo;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.job.JobDefinition;
import org.devlive.pipeforge.core.job.Location;
import org.devlive.pipeforge.core.job.NodeDefinition;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.ComponentProvider;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.core.plan.PluginComponents;
import org.devlive.pipeforge.core.plan.StaticComponents;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.local.LocalEngine;
import org.devlive.pipeforge.server.config.JobPlanning;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Dry runs and data previews of the job editor. The job is cut after the node to preview, its sinks
 * are replaced by a preview buffer, and it runs in the server with the local engine at parallelism
 * 1 until the buffer holds {@code limit} rows of a table, the job ends, or the time is up. Nothing
 * is written to any target: the job's sinks are never planned.
 */
@Service
public class PreviewService implements AutoCloseable {

    /** The most rows of a table a preview returns. */
    public static final int MAX_LIMIT = 1000;

    /** The longest a preview may run. */
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(2);

    /** Previews that run at the same time; more wait for one to end. */
    static final int CONCURRENT = 2;

    private final JobPlanning planning;
    private final Semaphore running = new Semaphore(CONCURRENT);
    private final LocalEngine engine = new LocalEngine(1);

    public PreviewService(JobPlanning planning) {
        this.planning = planning;
    }

    /** How a preview ended. */
    public enum Outcome {
        /** The job read all its rows. */
        COMPLETE,
        /** The buffer held {@code limit} rows of a table; the job was stopped there. */
        LIMIT,
        /** The time was up; the job was stopped with the rows it had. */
        TIMEOUT,
        /** The job failed; {@code error} says why. */
        FAILED
    }

    /**
     * The rows of one table at the previewed node.
     *
     * @param truncated whether the table had more rows than the limit
     */
    public record PreviewTable(
            String table, List<PreviewColumn> columns, List<List<@Nullable Object>> rows, boolean truncated) {}

    /** A column with its logical type. */
    public record PreviewColumn(String name, String type) {}

    /**
     * The outcome of a preview.
     *
     * @param node the node whose output the rows are
     * @param rowsRead rows the sources read
     */
    public record PreviewResult(
            String node,
            Outcome outcome,
            List<PreviewTable> tables,
            long rowsRead,
            long elapsedMs,
            @Nullable ErrorInfo error) {}

    /**
     * Previews the output of {@code node}, or what the sink receives when it is {@code null} (a dry run).
     *
     * @throws PipeForgeException {@code PF-CONF-0007} for an unknown node or a job without a sink to
     *     dry-run, and what planning reports for an invalid job
     */
    public PreviewResult preview(
            @Nullable String definition,
            @Nullable Map<String, String> variables,
            @Nullable Instant scheduledTime,
            @Nullable String node,
            int limit,
            Duration timeout,
            String language)
            throws InterruptedException {
        int rows = Math.max(1, Math.min(limit, MAX_LIMIT));
        Duration time =
                timeout.compareTo(MAX_TIMEOUT) > 0 || timeout.isNegative() || timeout.isZero() ? MAX_TIMEOUT : timeout;
        JobDefinition job = planning.parse(definition, variables, scheduledTime);
        String target = target(job, node);
        PreviewSink.Buffer buffer = PreviewSink.open(rows);
        if (!running.tryAcquire(time.toMillis(), TimeUnit.MILLISECONDS)) {
            PreviewSink.close(buffer);
            throw PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                    .param("reason", "the server is busy with other previews; try again")
                    .build();
        }
        long started = System.nanoTime();
        try (ComponentProvider components = new Components(planning)) {
            JobPlan plan = planning.plan(rewrite(job, target, buffer.id), components);
            JobHandle handle = engine.submit(plan, SubmitOptions.defaults());
            long deadline = started + time.toNanos();
            Outcome outcome = null;
            JobResult result = null;
            while (result == null) {
                if (buffer.full() && outcome == null) {
                    outcome = Outcome.LIMIT;
                    handle.cancel();
                } else if (System.nanoTime() > deadline && outcome == null) {
                    outcome = Outcome.TIMEOUT;
                    handle.cancel();
                }
                try {
                    result = handle.result().get(20, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // not yet
                } catch (java.util.concurrent.ExecutionException e) {
                    throw PipeForgeException.wrap(e.getCause());
                }
            }
            ErrorInfo error = null;
            if (outcome == null) {
                if (result.status() == JobStatus.SUCCESS) {
                    outcome = Outcome.COMPLETE;
                } else {
                    outcome = Outcome.FAILED;
                    error = result.error() == null ? null : result.error().toErrorInfo(language);
                }
            }
            PlannedNode sink = plan.nodes().stream()
                    .filter(n -> n.kind() == NodeKind.SINK)
                    .findFirst()
                    .orElseThrow();
            return new PreviewResult(
                    target,
                    outcome,
                    tables(sink.inputTables(), buffer),
                    result.metrics().rowsRead(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    error);
        } finally {
            PreviewSink.close(buffer);
            running.release();
        }
    }

    /** The node to preview: the given one, the input of a sink, or for a dry run the input of the job's sink. */
    static String target(JobDefinition job, @Nullable String node) {
        Map<String, NodeDefinition> nodes = new LinkedHashMap<>();
        job.nodes().forEach(n -> nodes.put(n.id(), n));
        if (node != null && !node.isBlank()) {
            NodeDefinition chosen = nodes.get(node);
            if (chosen == null) {
                throw invalid("the job has no node '" + node + "'");
            }
            if (chosen.kind() != NodeKind.SINK) {
                return chosen.id();
            }
            return single(chosen.inputs(), "sink '" + node + "'");
        }
        List<NodeDefinition> sinks =
                job.nodes().stream().filter(n -> n.kind() == NodeKind.SINK).toList();
        if (sinks.size() != 1) {
            throw invalid("a dry run needs a job with one sink, not " + sinks.size() + "; preview a node instead");
        }
        return single(sinks.get(0).inputs(), "sink '" + sinks.get(0).id() + "'");
    }

    private static String single(List<String> inputs, String what) {
        if (inputs.size() != 1) {
            throw invalid("the " + what + " has " + inputs.size() + " inputs; preview one of them");
        }
        return inputs.get(0);
    }

    private static PipeForgeException invalid(String reason) {
        return PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                .param("reason", reason)
                .build();
    }

    /** The job up to {@code target} and its upstream nodes, at parallelism 1, into the preview sink. */
    static JobDefinition rewrite(JobDefinition job, String target, String buffer) {
        Map<String, NodeDefinition> nodes = new HashMap<>();
        job.nodes().forEach(n -> nodes.put(n.id(), n));
        Set<String> keep = new LinkedHashSet<>();
        Deque<String> open = new ArrayDeque<>(List.of(target));
        while (!open.isEmpty()) {
            String id = open.pop();
            if (keep.add(id)) {
                open.addAll(nodes.get(id).inputs());
            }
        }
        List<NodeDefinition> kept = new ArrayList<>();
        for (NodeDefinition node : job.nodes()) {
            if (keep.contains(node.id())) {
                kept.add(new NodeDefinition(
                        node.id(),
                        node.kind(),
                        node.type(),
                        node.plugin(),
                        node.inputs(),
                        node.inputsImplied(),
                        1,
                        node.options(),
                        node.location()));
            }
        }
        kept.add(new NodeDefinition(
                "pipeforge-preview",
                NodeKind.SINK,
                PreviewSink.TYPE,
                StaticComponents.PLUGIN,
                List.of(target),
                false,
                1,
                Map.of(PreviewSink.BUFFER.key(), buffer),
                Location.of("preview")));
        return new JobDefinition(
                job.version(), job.name(), job.description(), job.timeZone(), job.variables(), job.env(), kept);
    }

    private static List<PreviewTable> tables(List<CatalogTable> inputs, PreviewSink.Buffer buffer) {
        Map<TableId, List<Row>> rows = buffer.rows();
        List<PreviewTable> tables = new ArrayList<>();
        for (CatalogTable table : inputs) {
            List<PreviewColumn> columns = table.columns().stream()
                    .map(c -> new PreviewColumn(c.name(), c.type().toString()))
                    .toList();
            List<List<@Nullable Object>> values = new ArrayList<>();
            for (Row row : rows.getOrDefault(table.id(), List.of())) {
                List<@Nullable Object> line = new ArrayList<>(row.arity());
                for (int i = 0; i < row.arity(); i++) {
                    line.add(json(row.get(i)));
                }
                values.add(line);
            }
            tables.add(new PreviewTable(table.id().toQualifiedName(), columns, values, buffer.truncated(table.id())));
        }
        return tables;
    }

    /** A value as JSON shows it without loss: large integers, decimals, times and bytes as text. */
    static @Nullable Object json(@Nullable Object value) {
        if (value == null || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof Long l) {
            return Math.abs(l) <= (1L << 53) ? l : l.toString();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return value;
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? value : value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof byte[] bytes) {
            return "base64:" + Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(PreviewService::json).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, @Nullable Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), json(v)));
            return copy;
        }
        if (value instanceof Row row) {
            List<@Nullable Object> fields = new ArrayList<>();
            for (int i = 0; i < row.arity(); i++) {
                fields.add(json(row.get(i)));
            }
            return fields;
        }
        return value.toString();
    }

    /** The preview sink, built in, and every other component from the server's plugins. */
    private static final class Components implements ComponentProvider {
        private final StaticComponents builtin =
                StaticComponents.builder().sink(new PreviewSink.Factory()).build();
        private final PluginComponents plugins;

        Components(JobPlanning planning) {
            this.plugins = new PluginComponents(planning.plugins(), "preview");
        }

        @Override
        public Component resolve(NodeKind kind, String type, @Nullable String plugin) {
            if (kind == NodeKind.SINK && PreviewSink.TYPE.equals(type)) {
                return builtin.resolve(kind, type, StaticComponents.PLUGIN);
            }
            return plugins.resolve(kind, type, plugin);
        }

        @Override
        public void close() {
            plugins.close();
        }
    }

    @Override
    public void close() {
        engine.close();
    }
}
