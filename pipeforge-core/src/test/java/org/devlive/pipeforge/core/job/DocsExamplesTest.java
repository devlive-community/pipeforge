/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.devlive.pipeforge.core.TestComponents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parses every complete job (a YAML block starting with {@code version: 1}, or a JSON block with
 * a {@code "version"} field) in the documentation pages about jobs, in every language, so that the
 * examples stay valid. Plugins are not installed here, so only the DSL and variables are checked.
 */
class DocsExamplesTest {

    private static final Path CONTENT = Path.of("..", "docs", "content");
    private static final List<String> PAGES =
            List.of("reference/job-dsl.mdx", "reference/variables.mdx", "concepts/plan-and-explain.mdx");
    private static final Pattern BLOCK = Pattern.compile("(?ms)^```(yaml|json)\\n(.*?)^```");

    static Stream<String> examples() throws IOException {
        List<String> examples = new ArrayList<>();
        for (String language : List.of("en-US", "zh-CN")) {
            for (String page : PAGES) {
                String text = Files.readString(CONTENT.resolve(language).resolve(page), StandardCharsets.UTF_8);
                Matcher matcher = BLOCK.matcher(text);
                while (matcher.find()) {
                    String body = matcher.group(2);
                    if (body.startsWith("version: 1") || body.contains("\"version\": 1")) {
                        examples.add(language + "/" + page + "\n" + body);
                    }
                }
            }
        }
        return examples.stream();
    }

    @ParameterizedTest
    @MethodSource("examples")
    void everyDocumentedJobParses(String example) {
        String body = example.substring(example.indexOf('\n') + 1);
        JobDefinition job = TestComponents.parse(body);
        assertThat(job.nodes()).as(example).isNotEmpty();
    }

    @Test
    void theExamplesAreFound() throws IOException {
        assertThat(examples()).hasSize(14);
    }
}
