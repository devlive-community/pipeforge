/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.connector.fake.FakeSourceFactory;
import org.devlive.pipeforge.core.job.JobParser;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.Planner;
import org.devlive.pipeforge.core.plan.StaticComponents;
import org.devlive.pipeforge.core.variable.VariableContext;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.local.LocalEngine;
import org.devlive.pipeforge.testkit.TestRun;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every example job of the documentation that only uses the fake source, the field mapper and the
 * console sink runs on the local engine: it succeeds, its row counters reconcile, and it prints.
 * Unbounded examples run until they have printed, then are cancelled; examples whose topology the
 * local engine of this version cannot run are planned and reported as skipped.
 */
class DocsExamplesTest {

    private static final Path CONTENT = Path.of("..", "..", "docs", "content");
    private static final Pattern BLOCK = Pattern.compile("(?ms)^```yaml\\n(.*?)^```");
    private static final Pattern TYPE = Pattern.compile("type:\\s*([a-z0-9-]+)");
    private static final Pattern ENV = Pattern.compile("\\$\\{env:([A-Za-z0-9_]+)");
    private static final Set<String> RUNNABLE = Set.of("fake", "field-mapper", "console");
    private static final Pattern OUTPUT = Pattern.compile("(?s)\\A\\s*```text\\n(.*?)^```", Pattern.MULTILINE);

    private static LocalEngine engine;
    private static CapturedOutput output;

    @BeforeAll
    static void start() {
        engine = new LocalEngine();
        output = new CapturedOutput();
    }

    @AfterAll
    static void stop() {
        output.close();
        engine.close();
    }

    /** An example; on the console page, {@code output} is the text block that follows it, if any. */
    record Example(String page, int index, String yaml, @Nullable String output) {
        @Override
        public String toString() {
            return page + " #" + index;
        }
    }

    static Stream<Example> examples() throws IOException {
        List<Example> examples = new ArrayList<>();
        List<Path> pages;
        try (Stream<Path> files = Files.walk(CONTENT)) {
            pages = files.filter(p -> p.toString().endsWith(".mdx")).sorted().toList();
        }
        for (Path page : pages) {
            String content = Files.readString(page, StandardCharsets.UTF_8);
            Matcher block = BLOCK.matcher(content);
            int index = 0;
            while (block.find()) {
                String yaml = block.group(1);
                index++;
                if (!yaml.startsWith("version: 1") || !yaml.contains("type: console")) {
                    continue;
                }
                Matcher type = TYPE.matcher(yaml);
                boolean runnable = true;
                while (type.find()) {
                    runnable &= RUNNABLE.contains(type.group(1));
                }
                if (runnable) {
                    String name = CONTENT.relativize(page).toString().replace('\\', '/');
                    String output = null;
                    if (name.endsWith("plugins/sink/console.mdx")) {
                        Matcher text = OUTPUT.matcher(content.substring(block.end()));
                        output = text.find() ? text.group(1) : null;
                    }
                    examples.add(new Example(name, index, yaml, output));
                }
            }
        }
        return examples.stream();
    }

    static JobPlan plan(String yaml) {
        Map<String, String> environment = new HashMap<>();
        Matcher env = ENV.matcher(yaml);
        while (env.find()) {
            environment.put(env.group(1), "example");
        }
        VariableContext variables = VariableContext.builder()
                .scheduledTime(Instant.parse("2026-11-07T00:00:00Z"))
                .defaultTimeZone(ZoneId.of("UTC"))
                .environment(environment)
                .build();
        StaticComponents components = StaticComponents.builder()
                .source(new FakeSourceFactory())
                .transform(new FieldMapperFactory())
                .sink(new ConsoleSinkFactory())
                .build();
        return new Planner(components).plan(JobParser.parse(yaml, variables));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void runs(Example example) throws Exception {
        output.clear();
        JobPlan plan = plan(example.yaml());
        JobHandle handle;
        try {
            handle = engine.submit(plan, SubmitOptions.defaults());
        } catch (PipeForgeException e) {
            // Planned, but this version of the local engine runs one chain of equal parallelism only.
            Assumptions.assumeFalse(
                    e.code().equals(ErrorCode.TOPOLOGY_UNSUPPORTED),
                    "the local engine cannot run it yet: " + e.getMessage());
            throw e;
        }
        if (example.yaml().contains("unbounded: true")) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (output.text().isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            handle.cancel();
            JobResult result = handle.result().get(60, TimeUnit.SECONDS);
            assertThat(result.status()).as(example.toString()).isEqualTo(JobStatus.CANCELLED);
        } else {
            JobResult result = handle.result().get(2, TimeUnit.MINUTES);
            new TestRun(example.toString(), result).requireSuccess().requireReconciled();
        }
        assertThat(output.text()).as("%s prints rows", example).isNotEmpty();
        if (example.output() != null) {
            assertThat(output.text()).as("the output shown on %s", example).isEqualTo(example.output());
        }
    }

    @Test
    void theRunnableExamplesAreFound() throws IOException {
        List<Example> all = examples().toList();
        assertThat(all).hasSizeGreaterThanOrEqualTo(18);
        assertThat(all).anyMatch(e -> e.yaml().contains("unbounded: true"));
        assertThat(all).anyMatch(e -> e.page().contains("field-mapper"));
        assertThat(all).filteredOn(e -> e.output() != null).hasSize(4);
    }
}
