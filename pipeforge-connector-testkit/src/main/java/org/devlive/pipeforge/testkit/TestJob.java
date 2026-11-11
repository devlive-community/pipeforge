/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.factory.Factory;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.factory.TransformFactory;
import org.devlive.pipeforge.core.job.JobParser;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.Planner;
import org.devlive.pipeforge.core.plan.StaticComponents;
import org.devlive.pipeforge.core.variable.VariableContext;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * A job built from factory instances: a source, transforms and a sink in a line. The factories are
 * used as they are, without plugin packages, and the job is planned by the same planner as any
 * other job, so option rules, schemas and errors behave as in production.
 *
 * <pre>{@code
 * JobPlan plan = TestJob.named("read-all")
 *         .source(new MySourceFactory(), Map.of("table", "demo.orders"))
 *         .sink(TestkitConnectors.collect(), Map.of("dataset", out))
 *         .parallelism(4)
 *         .plan();
 * }</pre>
 */
public final class TestJob {

    /** The time zone of testkit jobs, chosen to differ from UTC. */
    public static final ZoneId TIME_ZONE = ZoneId.of("Asia/Shanghai");

    private record Node(String id, String kind, Factory factory, Map<String, Object> options) {}

    private final String name;
    private final List<Node> nodes = new ArrayList<>();
    private int parallelism = 1;

    private TestJob(String name) {
        this.name = name;
    }

    /**
     * A job named after {@code name}: characters a job name cannot hold, e.g. those of {@code
     * DECIMAL(10, 2)}, become {@code -}.
     */
    public static TestJob named(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9_.-]+", "-").replaceAll("^-+|-+$", "");
        if (clean.isEmpty()) {
            clean = "testkit";
        }
        return new TestJob(clean.length() > 128 ? clean.substring(0, 128) : clean);
    }

    public TestJob source(SourceFactory factory, Map<String, ?> options) {
        return add("src", "source", factory, options);
    }

    public TestJob transform(TransformFactory factory, Map<String, ?> options) {
        return add("t" + nodes.size(), "transform", factory, options);
    }

    public TestJob sink(SinkFactory factory, Map<String, ?> options) {
        return add("out", "sink", factory, options);
    }

    public TestJob parallelism(int value) {
        parallelism = value;
        return this;
    }

    public String name() {
        return name;
    }

    private TestJob add(String id, String kind, Factory factory, Map<String, ?> options) {
        nodes.add(new Node(id, kind, factory, new LinkedHashMap<>(options)));
        return this;
    }

    /** The job definition as YAML, as a user would write it. */
    public String yaml() {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("version", JobParser.VERSION);
        job.put("name", name);
        job.put("env", Map.of("parallelism", parallelism, "timezone", TIME_ZONE.getId()));
        for (String kind : List.of("source", "transform", "sink")) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Node node : nodes) {
                if (node.kind().equals(kind)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", node.id());
                    entry.put("type", node.factory().identifier());
                    entry.putAll(node.options());
                    list.add(entry);
                }
            }
            if (!list.isEmpty()) {
                job.put(kind, list);
            }
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(job);
    }

    /**
     * Plans the job.
     *
     * @throws org.devlive.pipeforge.common.error.PipeForgeException when planning fails, e.g. an
     *     option is missing
     */
    public JobPlan plan() {
        StaticComponents.Builder components = StaticComponents.builder();
        for (Node node : nodes) {
            switch (node.kind()) {
                case "source" -> components.source((SourceFactory) node.factory());
                case "transform" -> components.transform((TransformFactory) node.factory());
                default -> components.sink((SinkFactory) node.factory());
            }
        }
        VariableContext context = VariableContext.builder()
                .scheduledTime(Instant.parse("2026-01-01T00:00:00Z"))
                .defaultTimeZone(TIME_ZONE)
                .environment(Map.of())
                .build();
        return new Planner(components.build()).plan(JobParser.parse(yaml(), context));
    }
}
