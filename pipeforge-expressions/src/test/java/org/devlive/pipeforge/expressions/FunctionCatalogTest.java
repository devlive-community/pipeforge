/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.devlive.pipeforge.api.row.GenericRow;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.api.type.DataType;
import org.devlive.pipeforge.api.type.DataTypes;
import org.devlive.pipeforge.api.type.TypeRoot;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.expressions.function.FunctionCatalog;
import org.devlive.pipeforge.expressions.function.FunctionCatalog.Entry;
import org.devlive.pipeforge.expressions.function.FunctionCatalog.Example;
import org.devlive.pipeforge.expressions.function.FunctionDefinition;
import org.devlive.pipeforge.expressions.function.builtin.BuiltinFunctions;
import org.devlive.pipeforge.expressions.runtime.R;
import org.junit.jupiter.api.Test;

/**
 * Keeps the function reference true: the catalog documents exactly the registered functions,
 * every documented signature type-checks to the documented result, and every example evaluates
 * (compiled and interpreted) to the documented result.
 */
class FunctionCatalogTest {

    private static final List<Entry> CATALOG = FunctionCatalog.load();
    private static final ExpressionEngine ENGINE = new ExpressionEngine();
    private static final InputSchema EMPTY = InputSchema.of(List.of());
    private static final Row ROW = GenericRow.of(Fixtures.TABLE);

    @Test
    void documentsExactlyTheRegisteredFunctionsAndTheSyntax() {
        Set<String> documented = CATALOG.stream().map(Entry::name).collect(Collectors.toCollection(TreeSet::new));
        assertThat(documented).hasSize(CATALOG.size());
        Set<String> expected = ENGINE.functions().all().stream()
                .map(FunctionDefinition::name)
                .collect(Collectors.toCollection(TreeSet::new));
        expected.addAll(BuiltinFunctions.SYNTAX);
        assertThat(documented).isEqualTo(expected);
        Set<String> syntax = CATALOG.stream()
                .filter(e -> ENGINE.functions().find(e.name()).isEmpty())
                .map(Entry::name)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(syntax).isEqualTo(new TreeSet<>(BuiltinFunctions.SYNTAX));
    }

    @Test
    void everyEntryIsComplete() {
        for (Entry entry : CATALOG) {
            assertThat(FunctionCatalog.CATEGORIES).as(entry.name()).contains(entry.category());
            assertThat(entry.description().get("en-US")).as(entry.name()).isNotBlank();
            assertThat(entry.description().get("zh-CN")).as(entry.name()).isNotBlank();
            assertThat(entry.examples()).as(entry.name()).isNotEmpty();
            assertThat(entry.nulls().keySet()).as(entry.name()).isIn(Set.of(), Set.of("en-US", "zh-CN"));
            if (entry.signatures().isEmpty()) {
                assertThat(entry.syntax())
                        .as("%s documents its syntax", entry.name())
                        .isNotBlank();
            }
        }
    }

    @Test
    void determinismMatchesTheRegistry() {
        for (Entry entry : CATALOG) {
            ENGINE.functions().find(entry.name()).ifPresent(definition -> assertThat(entry.deterministic())
                    .as(entry.name())
                    .isEqualTo(definition.deterministic()));
        }
    }

    @Test
    void documentedSignaturesTypeCheck() {
        List<String> problems = new ArrayList<>();
        for (Entry entry : CATALOG) {
            for (String signature : entry.signatures()) {
                String[] parts = signature.split(" → ");
                String call = parts[0];
                String expected = parts[1];
                String expression = call;
                int open = call.indexOf('(');
                if (open > 0 && !call.endsWith("()")) {
                    String arguments = call.substring(open + 1, call.length() - 1);
                    List<String> casts = new ArrayList<>();
                    for (String type : splitTypes(arguments)) {
                        casts.add("CAST(NULL AS " + castName(type) + ")");
                    }
                    expression = call.substring(0, open + 1) + String.join(", ", casts) + ")";
                }
                try {
                    String actual =
                            ENGINE.analyze(expression, EMPTY).type().nullable().toString();
                    if (!actual.equals(expected)) {
                        problems.add(signature + ": analyzer says " + actual);
                    }
                } catch (PipeForgeException e) {
                    problems.add(signature + ": " + e.getMessage());
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    /** Splits "DECIMAL(10, 2), INT32" at top-level commas. */
    private static List<String> splitTypes(String text) {
        List<String> types = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '<') {
                depth++;
            } else if (c == ')' || c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                types.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        types.add(text.substring(start).trim());
        return types;
    }

    private static String castName(String type) {
        if (type.startsWith("TIMESTAMP_LTZ")) {
            DataType parsed = DataTypes.parse(type);
            return "TIMESTAMP(" + parsed.precision() + ") WITH LOCAL TIME ZONE";
        }
        return type.equals("BYTES") ? "VARBINARY" : type;
    }

    @Test
    void examplesProduceTheDocumentedResults() {
        List<String> problems = new ArrayList<>();
        for (Entry entry : CATALOG) {
            for (Example example : entry.examples()) {
                String compiled = run(example.expression(), true);
                String interpreted = run(example.expression(), false);
                if (!compiled.equals(interpreted)) {
                    problems.add(example.expression() + ": compiled " + compiled + " but interpreted " + interpreted);
                } else if (!compiled.equals(example.result())) {
                    problems.add(entry.name() + ": " + example.expression() + " gives " + compiled + ", documented "
                            + example.result());
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    /** The result text: NULL, ERROR: reason, TRUE/FALSE, [a, b] or the value as CAST to STRING. */
    static String run(String text, boolean compile) {
        Expression expression;
        try {
            expression = ENGINE.analyze(text, EMPTY);
        } catch (PipeForgeException e) {
            return "INVALID: " + e.params().get("reason");
        }
        Evaluator evaluator = compile
                ? ENGINE.compile(List.of(expression), OnError.FAIL, Fixtures.SHANGHAI)
                : ENGINE.interpret(List.of(expression), OnError.FAIL, Fixtures.SHANGHAI);
        try {
            return render(evaluator.evaluate(ROW)[0], expression.type());
        } catch (PipeForgeException e) {
            return "ERROR: " + e.params().get("reason");
        }
    }

    private static String render(Object value, DataType type) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                items.add(render(item, type.elementType()));
            }
            return "[" + String.join(", ", items) + "]";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (type.root() == TypeRoot.MAP) {
            return String.valueOf((Map<?, ?>) value);
        }
        return (String) R.cast(value, type, DataTypes.STRING(), Fixtures.SHANGHAI.timeZone());
    }
}
