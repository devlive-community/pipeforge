/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.row.Row;
import org.devlive.pipeforge.testkit.SourceContract;
import org.devlive.pipeforge.testkit.SourceUnderTest;
import org.devlive.pipeforge.testkit.data.TestTable;
import org.jspecify.annotations.Nullable;

/** The fake source replays the testkit's tables as literal rows. */
class FakeSourceContractTest extends SourceContract {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** The options that make the fake source replay {@code table}. */
    static Map<String, Object> options(TestTable table) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("table", table.table().id().toString());
        options.put(
                "columns",
                table.table().columns().stream()
                        .map(c -> c.name() + " " + c.type())
                        .toList());
        options.put("primary-key", table.table().primaryKey());
        List<String> data = new ArrayList<>();
        for (Row row : table.rows()) {
            ArrayNode values = NODES.arrayNode();
            for (int i = 0; i < row.arity(); i++) {
                values.add(json(row.get(i)));
            }
            data.add(values.toString());
        }
        options.put("data", data);
        return options;
    }

    static JsonNode json(@Nullable Object value) {
        if (value == null) {
            return NODES.nullNode();
        }
        if (value instanceof BigDecimal decimal) {
            return NODES.textNode(decimal.toPlainString());
        }
        if (value instanceof Row row) {
            ArrayNode fields = NODES.arrayNode();
            for (int i = 0; i < row.arity(); i++) {
                fields.add(json(row.get(i)));
            }
            return fields;
        }
        if (value instanceof List<?> list) {
            ArrayNode array = NODES.arrayNode();
            list.forEach(element -> array.add(json(element)));
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode object = NODES.objectNode();
            map.forEach((k, v) -> object.set(String.valueOf(k), json(v)));
            return object;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String || value instanceof byte[]) {
            return JSON.valueToTree(value);
        }
        return NODES.textNode(value.toString());
    }

    @Override
    protected SourceUnderTest source() {
        return new SourceUnderTest() {
            @Override
            public SourceFactory factory() {
                return new FakeSourceFactory();
            }

            @Override
            public Map<String, Object> options(TestTable table) {
                return FakeSourceContractTest.options(table);
            }

            @Override
            public int largeRowCount() {
                return 50_000;
            }

            @Override
            public Map<String, Map<String, Object>> splitStrategies() {
                return Map.of("one split", Map.of("splits", 1), "many splits", Map.of("splits", 64));
            }
        };
    }
}
