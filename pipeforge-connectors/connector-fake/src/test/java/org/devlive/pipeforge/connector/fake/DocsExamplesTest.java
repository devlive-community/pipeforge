/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.core.job.JobDefinition;
import org.devlive.pipeforge.core.job.JobParser;
import org.devlive.pipeforge.core.job.NodeDefinition;
import org.devlive.pipeforge.core.variable.VariableContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every job on the plugin page parses, and its fake nodes have valid options. */
class DocsExamplesTest {

    private static final Path CONTENT = Path.of("..", "..", "docs", "content");
    private static final Pattern BLOCK = Pattern.compile("(?ms)^```yaml\\n(.*?)^```");

    static Stream<String> examples() throws IOException {
        List<String> examples = new ArrayList<>();
        for (String language : List.of("en-US", "zh-CN")) {
            Path page = CONTENT.resolve(language).resolve("plugins/source/fake.mdx");
            Matcher matcher = BLOCK.matcher(Files.readString(page, StandardCharsets.UTF_8));
            while (matcher.find()) {
                if (matcher.group(1).startsWith("version: 1")) {
                    examples.add(language + "\n" + matcher.group(1));
                }
            }
        }
        return examples.stream();
    }

    @ParameterizedTest
    @MethodSource("examples")
    void everyExampleHasValidFakeOptions(String example) {
        String yaml = example.substring(example.indexOf('\n') + 1);
        JobDefinition job = JobParser.parse(
                yaml,
                VariableContext.builder()
                        .scheduledTime(Instant.parse("2026-01-01T00:00:00Z"))
                        .defaultTimeZone(ZoneId.of("UTC"))
                        .environment(Map.of())
                        .build());
        List<NodeDefinition> fakes =
                job.nodes().stream().filter(n -> n.type().equals("fake")).toList();
        assertThat(fakes).as(example).isNotEmpty();
        OptionRule rule = new FakeSourceFactory().optionRule();
        for (NodeDefinition node : fakes) {
            ReadonlyConfig config = ReadonlyConfig.of(node.options());
            assertThat(rule.validate(config, node.id())).as(example).isEmpty();
            assertThat(FakeConfig.read(config, node.id()).tables()).isNotEmpty();
        }
    }

    @Test
    void theExamplesAreFound() throws IOException {
        assertThat(examples()).hasSize(10);
    }
}
