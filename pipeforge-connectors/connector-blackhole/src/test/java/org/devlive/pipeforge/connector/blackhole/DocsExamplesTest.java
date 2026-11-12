/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.blackhole;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.devlive.pipeforge.connector.fake.FakeSourceFactory;
import org.devlive.pipeforge.core.job.JobParser;
import org.devlive.pipeforge.core.plan.Planner;
import org.devlive.pipeforge.core.plan.StaticComponents;
import org.devlive.pipeforge.core.variable.VariableContext;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.local.LocalEngine;
import org.devlive.pipeforge.testkit.TestRun;
import org.devlive.pipeforge.transform.fieldmapper.FieldMapperFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every example job of the plugin page, in both languages, runs on the local engine and reconciles. */
class DocsExamplesTest {

    private static final Path CONTENT = Path.of("..", "..", "docs", "content");
    private static final Pattern BLOCK = Pattern.compile("(?ms)^```yaml\\n(.*?)^```");

    private static LocalEngine engine;

    @BeforeAll
    static void start() {
        engine = new LocalEngine();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    static Stream<String> examples() throws IOException {
        List<String> examples = new ArrayList<>();
        for (String language : List.of("en-US", "zh-CN")) {
            Path page = CONTENT.resolve(language).resolve("plugins/sink/blackhole.mdx");
            Matcher block = BLOCK.matcher(Files.readString(page, StandardCharsets.UTF_8));
            while (block.find()) {
                if (block.group(1).startsWith("version: 1")) {
                    examples.add(block.group(1));
                }
            }
        }
        return examples.stream();
    }

    @ParameterizedTest
    @MethodSource("examples")
    void runs(String yaml) throws Exception {
        VariableContext variables = VariableContext.builder()
                .scheduledTime(Instant.parse("2026-11-07T00:00:00Z"))
                .defaultTimeZone(ZoneId.of("UTC"))
                .environment(Map.of())
                .build();
        StaticComponents components = StaticComponents.builder()
                .source(new FakeSourceFactory())
                .transform(new FieldMapperFactory())
                .sink(new BlackholeSinkFactory())
                .build();
        JobResult result = engine.submit(
                        new Planner(components).plan(JobParser.parse(yaml, variables)), SubmitOptions.defaults())
                .result()
                .get(2, TimeUnit.MINUTES);
        new TestRun(yaml.lines().filter(l -> l.startsWith("name:")).findFirst().orElse(yaml), result)
                .requireSuccess()
                .requireReconciled();
        assertThat(result.metrics().rowsWritten()).isPositive();
        if (yaml.contains("rows-per-second")) {
            // The page says the source waits for a throttled sink.
            assertThat(result.metrics().readerWaitMs()).isPositive();
        }
    }

    @Test
    void theExamplesAreFound() throws IOException {
        assertThat(examples()).hasSize(8);
    }
}
