/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.transform.fieldmapper;

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
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.connector.fake.FakeSourceFactory;
import org.devlive.pipeforge.core.job.JobParser;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.Planner;
import org.devlive.pipeforge.core.plan.StaticComponents;
import org.devlive.pipeforge.core.variable.VariableContext;
import org.devlive.pipeforge.testkit.connector.TestkitConnectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Plans every job of the plugin page, in both languages, with the fake source and a stand-in console sink. */
class DocsExamplesTest {

    private static final Path CONTENT = Path.of("..", "..", "docs", "content");
    private static final Pattern BLOCK = Pattern.compile("(?ms)^```yaml\\n(.*?)^```");

    /** The output columns the text of the page promises for some of the examples. */
    private static final Map<String, List<String>> COLUMNS = Map.of(
            "field-mapper-demo", List.of("order_id", "name", "amount_cents", "created_at", "batch_date"),
            "orders-to-warehouse",
                    List.of("order_id", "customer_name", "amount", "created_at", "customer_key", "source_system"),
            "export-columns", List.of("email", "user_id", "age"),
            "shards", List.of("id", "order_code"));

    static Stream<String> examples() throws IOException {
        List<String> examples = new ArrayList<>();
        for (String language : List.of("en-US", "zh-CN")) {
            Path page = CONTENT.resolve(language).resolve("plugins/transform/field-mapper.mdx");
            Matcher matcher = BLOCK.matcher(Files.readString(page, StandardCharsets.UTF_8));
            while (matcher.find()) {
                if (matcher.group(1).startsWith("version: 1")) {
                    examples.add(language + "\n" + matcher.group(1));
                }
            }
        }
        return examples.stream();
    }

    /** The console sink is not part of this module; this one accepts every table. */
    private static SinkFactory console() {
        SinkFactory collect = TestkitConnectors.collect();
        return new SinkFactory() {
            @Override
            public String identifier() {
                return "console";
            }

            @Override
            public OptionRule optionRule() {
                return OptionRule.builder().build();
            }

            @Override
            public Sink<?, ?> createSink(FactoryContext context) throws Exception {
                return collect.createSink(context);
            }
        };
    }

    @ParameterizedTest
    @MethodSource("examples")
    void everyExamplePlans(String example) {
        String yaml = example.substring(example.indexOf('\n') + 1);
        VariableContext context = VariableContext.builder()
                .scheduledTime(Instant.parse("2026-01-01T00:00:00Z"))
                .defaultTimeZone(ZoneId.of("UTC"))
                .environment(Map.of())
                .build();
        StaticComponents components = StaticComponents.builder()
                .source(new FakeSourceFactory())
                .transform(new FieldMapperFactory())
                .sink(console())
                .build();
        JobPlan plan = new Planner(components).plan(JobParser.parse(yaml, context));
        List<CatalogTable> out = plan.nodes().stream()
                .filter(n -> n.type().equals("field-mapper"))
                .findFirst()
                .orElseThrow()
                .outputTables();
        assertThat(out).as(example).isNotEmpty();
        List<String> expected = COLUMNS.get(plan.name());
        if (expected != null) {
            assertThat(out)
                    .allSatisfy(t -> assertThat(t.columnNames()).as(plan.name()).isEqualTo(expected));
        }
    }

    @Test
    void theExamplesAreFound() throws IOException {
        assertThat(examples()).hasSize(10);
    }
}
