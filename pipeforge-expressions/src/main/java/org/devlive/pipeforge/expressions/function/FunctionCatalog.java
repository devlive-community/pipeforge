/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The documentation of the built-in functions ({@code functions/builtin.yaml}): category,
 * signatures, descriptions in English and Chinese, NULL behavior and examples. The build checks it
 * against the registry and runs every example; the documentation site renders it.
 */
public final class FunctionCatalog {

    /** Resource with the catalog. */
    public static final String RESOURCE = "functions/builtin.yaml";

    /** Categories in documentation order. */
    public static final List<String> CATEGORIES = List.of("comparison", "numeric", "string", "datetime", "conversion");

    /** An example: an expression and the text of its result ({@code NULL}, or {@code ERROR: reason}). */
    public record Example(String expression, String result) {}

    /** One documented function. */
    public record Entry(
            String name,
            String category,
            @Nullable String syntax,
            boolean deterministic,
            List<String> signatures,
            Map<String, String> description,
            Map<String, String> nulls,
            List<Example> examples) {

        public Entry {
            signatures = List.copyOf(signatures);
            description = Map.copyOf(description);
            nulls = Map.copyOf(nulls);
            examples = List.copyOf(examples);
        }
    }

    private FunctionCatalog() {}

    /** Loads the catalog from the class path. */
    public static List<Entry> load() {
        try (InputStream in = FunctionCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Entry> parse(String text) {
        List<Map<String, Object>> raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        List<Entry> entries = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            List<Example> examples = new ArrayList<>();
            for (Map<String, Object> example : (List<Map<String, Object>>) item.getOrDefault("examples", List.of())) {
                examples.add(
                        new Example(String.valueOf(example.get("expression")), String.valueOf(example.get("result"))));
            }
            entries.add(new Entry(
                    String.valueOf(item.get("name")),
                    String.valueOf(item.get("category")),
                    (String) item.get("syntax"),
                    !Boolean.FALSE.equals(item.get("deterministic")),
                    (List<String>) item.getOrDefault("signatures", List.of()),
                    (Map<String, String>) item.getOrDefault("description", Map.of()),
                    (Map<String, String>) item.getOrDefault("nulls", Map.of()),
                    examples));
        }
        return entries;
    }
}
