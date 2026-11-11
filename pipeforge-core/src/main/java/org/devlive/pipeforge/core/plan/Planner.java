/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.api.capability.SupportsUpsert;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.factory.EngineKind;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.OptionType;
import org.devlive.pipeforge.api.option.OptionViolation;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkContext;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.api.source.SourceContext;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.transform.Transform;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.expression.EngineExpressions;
import org.devlive.pipeforge.core.job.Delivery;
import org.devlive.pipeforge.core.job.EnvOptions;
import org.devlive.pipeforge.core.job.JobDefinition;
import org.devlive.pipeforge.core.job.JobMode;
import org.devlive.pipeforge.core.job.NodeDefinition;
import org.devlive.pipeforge.core.job.NodeKind;
import org.devlive.pipeforge.core.job.Suggestions;
import org.jspecify.annotations.Nullable;

/**
 * Turns a {@link JobDefinition} into a {@link JobPlan}:
 *
 * <ol>
 *   <li>checks the inputs of every node, that the graph has no cycle and that every node leads to
 *       a sink;
 *   <li>resolves the component of every node and merges its options: node, then env defaults
 *       ({@code env.source.*}), then cluster defaults, then the plugin defaults;
 *   <li>validates the options with the plugin's {@link OptionRule};
 *   <li>derives the schemas in topological order: the tables of each source, the output of each
 *       transform for its merged inputs, and lets each sink check what it receives;
 *   <li>derives the job mode from the sources and checks the delivery guarantee of the sinks.
 * </ol>
 *
 * <p>The first problem found is thrown as a {@link PipeForgeException} with its error code.
 */
public final class Planner {

    private final ComponentProvider components;
    private final ReadonlyConfig cluster;

    /**
     * @param components where node components come from
     * @param cluster cluster defaults in the env form: {@code parallelism}, {@code delivery},
     *     {@code source.fetch-size}, ...
     */
    public Planner(ComponentProvider components, ReadonlyConfig cluster) {
        this.components = components;
        this.cluster = cluster;
    }

    public Planner(ComponentProvider components) {
        this(components, ReadonlyConfig.empty());
    }

    public JobPlan plan(JobDefinition job) {
        Map<String, NodeDefinition> nodes = new LinkedHashMap<>();
        job.nodes().forEach(n -> nodes.put(n.id(), n));
        checkInputs(nodes);
        List<NodeDefinition> order = topologicalOrder(nodes);
        checkConnected(nodes);

        int parallelism = setting(job, EnvOptions.PARALLELISM);
        Delivery delivery = setting(job, EnvOptions.DELIVERY);
        JobContext context = new JobContext(job.name(), parallelism, job.timeZone());
        List<String> warnings = new ArrayList<>();
        Set<String> declared = new HashSet<>();
        Map<String, PlannedNode> planned = new LinkedHashMap<>();
        for (NodeDefinition node : order) {
            try {
                planned.put(node.id(), planNode(job, node, context, delivery, planned, declared, warnings));
            } catch (PipeForgeException e) {
                throw atNode(e, node.id());
            }
        }
        warnUnusedDefaults(job, declared, warnings);

        JobMode written =
                job.env().contains(EnvOptions.MODE) ? job.env().get(EnvOptions.MODE) : cluster.get(EnvOptions.MODE);
        boolean unbounded = planned.values().stream().anyMatch(n -> n.boundedness() == Boundedness.UNBOUNDED);
        if (written == JobMode.BATCH && unbounded) {
            PlannedNode source = planned.values().stream()
                    .filter(n -> n.boundedness() == Boundedness.UNBOUNDED)
                    .findFirst()
                    .orElseThrow();
            throw PipeForgeException.of(ErrorCode.BOUNDEDNESS_MISMATCH)
                    .param("node", source.id())
                    .scope(ErrorScope.node(source.id()))
                    .build();
        }
        JobMode mode = written != null ? written : unbounded ? JobMode.STREAMING : JobMode.BATCH;
        return new JobPlan(
                job.name(),
                job.description(),
                EngineKind.NATIVE,
                mode,
                written == null,
                delivery,
                parallelism,
                job.timeZone(),
                job.variables(),
                List.copyOf(planned.values()),
                warnings);
    }

    private <T> T setting(JobDefinition job, Option<T> option) {
        if (job.env().contains(option)) {
            return job.env().get(option);
        }
        T value = cluster.get(option);
        if (value == null) {
            throw new IllegalStateException("No default for " + option.key());
        }
        return value;
    }

    /**
     * The error with {@code node} in its scope when it names no node yet, e.g. a plugin that is not
     * installed, so that editors can point at the node.
     */
    static PipeForgeException atNode(PipeForgeException error, String node) {
        if (error.scope().node() != null) {
            return error;
        }
        PipeForgeException.Builder builder = PipeForgeException.of(error.code())
                .scope(error.scope().withNode(node))
                .kind(error.kind())
                .cause(error.getCause());
        error.params().forEach(builder::param);
        return builder.build();
    }

    // ------------------------------------------------------------------ graph

    private static void checkInputs(Map<String, NodeDefinition> nodes) {
        for (NodeDefinition node : nodes.values()) {
            Set<String> seen = new HashSet<>();
            for (String input : node.inputs()) {
                String reason = null;
                NodeDefinition upstream = nodes.get(input);
                if (input.equals(node.id())) {
                    reason = "a node cannot read its own output";
                } else if (upstream == null) {
                    List<String> producers = nodes.values().stream()
                            .filter(n -> n.kind() != NodeKind.SINK)
                            .map(NodeDefinition::id)
                            .toList();
                    reason = Suggestions.withSuggestion("the job has no node with this id", input, producers);
                } else if (upstream.kind() == NodeKind.SINK) {
                    reason = "'" + input + "' is a sink and produces no data";
                } else if (!seen.add(input)) {
                    reason = "the input is listed twice";
                }
                if (reason != null) {
                    throw PipeForgeException.of(ErrorCode.INPUT_INVALID)
                            .param("node", node.id())
                            .param("input", input)
                            .param("reason", reason)
                            .scope(ErrorScope.node(node.id()))
                            .build();
                }
            }
        }
    }

    /** Kahn's algorithm; ready nodes are taken in the order written. Throws on a cycle. */
    private static List<NodeDefinition> topologicalOrder(Map<String, NodeDefinition> nodes) {
        Map<String, Integer> pending = new LinkedHashMap<>();
        nodes.values().forEach(n -> pending.put(n.id(), n.inputs().size()));
        List<NodeDefinition> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (NodeDefinition node : nodes.values()) {
                if (!done.contains(node.id()) && done.containsAll(node.inputs())) {
                    done.add(node.id());
                    order.add(node);
                    progress = true;
                    break;
                }
            }
        }
        if (order.size() < nodes.size()) {
            throw PipeForgeException.of(ErrorCode.DAG_CYCLE)
                    .param("path", String.join(" -> ", cycle(nodes, done)))
                    .build();
        }
        return order;
    }

    /** A cycle among the nodes that could not be ordered, as a path that ends where it starts. */
    private static List<String> cycle(Map<String, NodeDefinition> nodes, Set<String> done) {
        String start = nodes.keySet().stream()
                .filter(id -> !done.contains(id))
                .findFirst()
                .orElseThrow();
        List<String> path = new ArrayList<>();
        String current = start;
        while (!path.contains(current)) {
            path.add(current);
            NodeDefinition node = nodes.get(current);
            current = node.inputs().stream()
                    .filter(i -> !done.contains(i))
                    .findFirst()
                    .orElseThrow();
        }
        // The walk follows inputs upstream; reverse it to follow the data, starting at the node written first.
        List<String> loop = new ArrayList<>(path.subList(path.indexOf(current), path.size()));
        Collections.reverse(loop);
        List<String> written = new ArrayList<>(nodes.keySet());
        String first =
                loop.stream().min(Comparator.comparingInt(written::indexOf)).orElseThrow();
        Collections.rotate(loop, -loop.indexOf(first));
        loop.add(first);
        return loop;
    }

    /** Every source and transform must lead to a sink. */
    private static void checkConnected(Map<String, NodeDefinition> nodes) {
        Set<String> reaches = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        nodes.values().stream().filter(n -> n.kind() == NodeKind.SINK).forEach(n -> queue.add(n.id()));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (reaches.add(id)) {
                queue.addAll(nodes.get(id).inputs());
            }
        }
        for (NodeDefinition node : nodes.values()) {
            if (!reaches.contains(node.id())) {
                throw PipeForgeException.of(ErrorCode.NODE_DISCONNECTED)
                        .param("node", node.id())
                        .scope(ErrorScope.node(node.id()))
                        .build();
            }
        }
    }

    // ------------------------------------------------------------------ nodes

    private PlannedNode planNode(
            JobDefinition job,
            NodeDefinition node,
            JobContext context,
            Delivery delivery,
            Map<String, PlannedNode> planned,
            Set<String> declared,
            List<String> warnings) {
        Component component = components.resolve(node.kind(), node.type(), node.plugin());
        Factory factory = component.factory();
        OptionRule rule = component.call("read the options of " + node.type(), factory::optionRule);
        Map<String, Object> values = new LinkedHashMap<>(node.options());
        List<PlannedOption> options = new ArrayList<>();
        Map<String, OptionOrigin> origins = new LinkedHashMap<>();
        node.options().keySet().forEach(k -> origins.put(k, OptionOrigin.NODE));
        ReadonlyConfig written = ReadonlyConfig.of(node.options());
        for (Option<?> option : rule.options()) {
            declared.add(node.kind().id() + "." + option.key());
            if (written.contains(option)) {
                continue;
            }
            String prefix = node.kind().id() + "." + option.key();
            if (!copyDefault(job.env(), prefix, option, values, origins, OptionOrigin.ENV)) {
                copyDefault(cluster, prefix, option, values, origins, OptionOrigin.CLUSTER);
            }
        }
        ReadonlyConfig config = ReadonlyConfig.of(values);
        List<OptionViolation> violations = rule.validate(config, node.id());
        if (!violations.isEmpty()) {
            throw violations.get(0).toException();
        }
        Set<String> sensitive = new HashSet<>();
        for (Option<?> option : rule.options()) {
            if (option.isSensitive()) {
                sensitive.add(option.key());
                sensitive.addAll(option.deprecatedKeys());
            }
        }
        origins.forEach((key, origin) -> {
            boolean secret = sensitive.contains(key);
            options.add(new PlannedOption(key, secret ? OptionRule.MASK : values.get(key), origin, secret));
        });
        for (Option<?> option : rule.options()) {
            if (!config.contains(option) && option.hasDefault()) {
                options.add(new PlannedOption(
                        option.key(), option.defaultValue(), OptionOrigin.DEFAULT, option.isSensitive()));
            }
        }

        int parallelism = node.parallelism() != null ? node.parallelism() : context.parallelism();
        Map<String, List<CatalogTable>> upstream = new LinkedHashMap<>();
        node.inputs().forEach(input -> upstream.put(input, planned.get(input).outputTables()));
        List<CatalogTable> inputTables = SchemaMerger.merge(node.id(), upstream);
        FactoryContext factoryContext = new PlanningContext(
                node.id(),
                config,
                context,
                inputTables,
                component.classLoader(),
                EngineExpressions.planning(context.timeZone()));

        Boundedness boundedness = null;
        List<CatalogTable> outputTables = List.of();
        switch (node.kind()) {
            case SOURCE -> {
                Source<?, ?> source = component.call(
                        "create source " + node.id(), () -> ((SourceFactory) factory).createSource(factoryContext));
                SourceContext sourceContext = new PlanningSourceContext(context, component.classLoader());
                outputTables = tables(component.call(
                        "derive the tables of " + node.id(), () -> source.producedTables(sourceContext)));
                boundedness = component.call("read the boundedness of " + node.id(), source::boundedness);
            }
            case TRANSFORM -> {
                Transform transform = component.call("create transform " + node.id(), () -> ((TransformFactory) factory)
                        .createTransform(factoryContext));
                outputTables = tables(component.call(
                        "derive the tables of " + node.id(), () -> transform.transformSchema(inputTables)));
            }
            case SINK -> {
                Sink<?, ?> sink = component.call(
                        "create sink " + node.id(), () -> ((SinkFactory) factory).createSink(factoryContext));
                SinkContext sinkContext = new PlanningSinkContext(context, node.id(), component.classLoader());
                component.call("validate the input of " + node.id(), () -> {
                    sink.validate(inputTables, sinkContext);
                    return null;
                });
                if (delivery == Delivery.EXACTLY_ONCE && !exactlyOnce(component, node.id(), sink)) {
                    throw PipeForgeException.of(ErrorCode.DELIVERY_UNSUPPORTED)
                            .param("node", node.id())
                            .param("guarantee", Delivery.AT_LEAST_ONCE.id())
                            .scope(ErrorScope.node(node.id()))
                            .build();
                }
            }
        }
        if (node.kind() != NodeKind.SINK && outputTables.isEmpty()) {
            warnings.add("node '" + node.id() + "' produces no tables");
        }
        return new PlannedNode(
                node.id(),
                node.kind(),
                node.type(),
                component.plugin(),
                node.inputs(),
                node.inputsImplied(),
                parallelism,
                boundedness,
                config,
                options,
                inputTables,
                outputTables,
                component);
    }

    /** Copies a default of the option from {@code from} (keys prefixed with the node kind). */
    private static boolean copyDefault(
            ReadonlyConfig from,
            String prefix,
            Option<?> option,
            Map<String, Object> values,
            Map<String, OptionOrigin> origins,
            OptionOrigin origin) {
        boolean copied = false;
        for (String key : from.keys()) {
            boolean entry = option.type() == OptionType.MAP && key.startsWith(prefix + ".");
            if (key.equals(prefix) || entry) {
                String target = option.key() + key.substring(prefix.length());
                values.put(target, from.raw(key));
                origins.put(target, origin);
                copied = true;
            }
        }
        return copied;
    }

    /** Warns about env defaults that no node of the kind declares. */
    private static void warnUnusedDefaults(JobDefinition job, Set<String> declared, List<String> warnings) {
        for (String key : job.env().keys()) {
            for (String prefix : EnvOptions.NODE_DEFAULT_PREFIXES) {
                if (key.startsWith(prefix + ".")
                        && declared.stream().noneMatch(d -> key.equals(d) || key.startsWith(d + "."))) {
                    warnings.add("env." + key + " is not an option of any " + prefix + " in the job");
                }
            }
        }
    }

    private static boolean exactlyOnce(Component component, String node, Sink<?, ?> sink) {
        if (sink instanceof SupportsUpsert) {
            return true;
        }
        return component.call(
                "read the committer of " + node, () -> sink.createCommitter().isPresent());
    }

    private static List<CatalogTable> tables(@Nullable List<CatalogTable> tables) {
        return tables == null ? List.of() : List.copyOf(tables);
    }

    private record PlanningContext(
            String nodeName,
            ReadonlyConfig options,
            JobContext job,
            List<CatalogTable> inputTables,
            ClassLoader classLoader,
            ExpressionCompiler expressions)
            implements FactoryContext {}

    private record PlanningSourceContext(JobContext job, ClassLoader classLoader) implements SourceContext {}

    private record PlanningSinkContext(JobContext job, String nodeName, ClassLoader classLoader)
            implements SinkContext {}
}
