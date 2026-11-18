/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The client against the server's OpenAPI document: every operation it calls exists with its
 * method, path and query parameters, and every field of its records is a property of the schema of
 * the answer, of a compatible type. A change of the API that breaks the client fails here.
 */
class ContractTest {

    static final JsonNode API = read();

    static JsonNode read() {
        try {
            return PipeForgeClient.JSON.readTree(
                    Path.of(System.getProperty("pipeforge.openapi")).toFile());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode operation(Endpoint endpoint) {
        return API.path("paths").path(endpoint.path).path(endpoint.method.toLowerCase(Locale.ROOT));
    }

    static JsonNode schema(JsonNode node) {
        String ref = node.path("$ref").asText("");
        return ref.isEmpty() ? node : API.at(ref.substring(1));
    }

    @Test
    void everyEndpointExistsWithItsQueryParameters() {
        for (Endpoint endpoint : Endpoint.values()) {
            JsonNode operation = operation(endpoint);
            assertThat(operation.isMissingNode())
                    .as("%s %s", endpoint.method, endpoint.path)
                    .isFalse();
            Set<String> query = new TreeSet<>();
            Set<String> path = new TreeSet<>();
            for (JsonNode parameter : operation.path("parameters")) {
                switch (parameter.path("in").asText()) {
                    case "query" -> query.add(parameter.path("name").asText());
                    case "path" -> path.add(parameter.path("name").asText());
                    default -> {
                        // headers such as Accept-Language
                    }
                }
            }
            assertThat(new TreeSet<>(endpoint.query))
                    .as("query parameters of %s", endpoint)
                    .isSubsetOf(query);
            for (String name : path) {
                assertThat(endpoint.path).as("path of %s", endpoint).contains("{" + name + "}");
            }
        }
    }

    @Test
    void everyRecordMatchesTheSchemaOfItsAnswer() {
        List<String> problems = new ArrayList<>();
        for (Endpoint endpoint : Endpoint.values()) {
            JsonNode responses = operation(endpoint).path("responses");
            JsonNode ok = responses.has("200") ? responses.path("200") : responses.path("201");
            JsonNode answer = ok.path("content").elements().hasNext()
                    ? ok.path("content").elements().next().path("schema")
                    : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            if (endpoint.answer == null) {
                continue;
            }
            if (endpoint.list) {
                assertThat(answer.path("type").asText())
                        .as("%s answers a list", endpoint)
                        .isEqualTo("array");
                answer = answer.path("items");
            }
            compare(endpoint.answer, schema(answer), endpoint.toString(), problems, new HashSet<>());
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void requestBodiesUseTheFieldsOfTheirSchemas() {
        Map<Endpoint, Set<String>> bodies = Map.of(
                Endpoint.SUBMIT_RUN, Set.of("definition", "variables", "scheduledTime"),
                Endpoint.CANCEL_RUN, Set.of("reason"),
                Endpoint.VALIDATE_JOB, Set.of("definition", "variables"),
                Endpoint.EXPLAIN_JOB, Set.of("definition", "variables"));
        bodies.forEach((endpoint, fields) -> {
            JsonNode body = schema(operation(endpoint)
                    .path("requestBody")
                    .path("content")
                    .path("application/json")
                    .path("schema"));
            Set<String> properties = new TreeSet<>();
            body.path("properties").fieldNames().forEachRemaining(properties::add);
            assertThat(fields).as("body of %s", endpoint).isSubsetOf(properties);
        });
    }

    /** Every component of {@code type} is a property of {@code schema}, with a matching type. */
    static void compare(Class<?> type, JsonNode schema, String where, List<String> problems, Set<Class<?>> seen) {
        if (!seen.add(type)) {
            return;
        }
        JsonNode properties = schema.path("properties");
        for (RecordComponent component : type.getRecordComponents()) {
            String at = where + "." + component.getName();
            JsonNode property = properties.path(component.getName());
            if (property.isMissingNode()) {
                problems.add(at + " is not in the schema");
                continue;
            }
            check(component.getGenericType(), property, at, problems, seen);
        }
    }

    static void check(Type type, JsonNode property, String at, List<String> problems, Set<Class<?>> seen) {
        JsonNode resolved = schema(property);
        String json = resolved.path("type").asText("");
        String format = resolved.path("format").asText("");
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            if (raw == List.class) {
                if (!json.equals("array")) {
                    problems.add(at + " is a list, the schema says " + json);
                } else {
                    check(parameterized.getActualTypeArguments()[0], resolved.path("items"), at + "[]", problems, seen);
                }
            } else if (raw == Map.class && !json.equals("object")) {
                problems.add(at + " is a map, the schema says " + json);
            }
            return;
        }
        Class<?> java = (Class<?>) type;
        String expected;
        if (java == String.class) {
            expected = "string";
        } else if (java == Instant.class) {
            expected = "string/date-time";
        } else if (java == long.class || java == Long.class) {
            expected = "integer/int64";
        } else if (java == int.class || java == Integer.class) {
            expected = "integer/int32";
        } else if (java == double.class || java == Double.class) {
            expected = "number/double";
        } else if (java == boolean.class || java == Boolean.class) {
            expected = "boolean";
        } else if (java == JsonNode.class) {
            return;
        } else if (java.isRecord()) {
            compare(java, resolved, at, problems, seen);
            return;
        } else {
            problems.add(at + " has the type " + java.getName() + ", which the test does not know");
            return;
        }
        String actual = format.isEmpty() ? json : json + "/" + format;
        if (!expected.equals(actual)) {
            problems.add(at + " is " + expected + ", the schema says " + actual);
        }
    }

    @Test
    void theContractTestFindsAFieldTheSchemaLacks() {
        record Wrong(String id, long notInTheApi, int job) {}
        List<String> problems = new ArrayList<>();
        compare(Wrong.class, API.at("/components/schemas/Run"), "Wrong", problems, new HashSet<>());
        assertThat(problems)
                .containsExactly(
                        "Wrong.notInTheApi is not in the schema", "Wrong.job is integer/int32, the schema says string");
    }
}
