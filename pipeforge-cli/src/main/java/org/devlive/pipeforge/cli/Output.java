/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Output formats: {@code table} for people, {@code json} and {@code yaml} with the fields of the
 * REST API for scripts.
 */
public final class Output {

    /** The formats of {@code -o}. */
    public enum Format {
        TABLE,
        JSON,
        YAML
    }

    /** Dates, times and durations as ISO-8601 text (2026-11-07T00:00:00Z, PT1S), as the REST API writes them. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    private Output() {}

    /** A structured value as JSON or YAML text, ending with a line break. */
    public static String render(Object value, Format format) {
        try {
            if (format == Format.YAML) {
                Object tree = JSON.convertValue(value, Object.class);
                DumperOptions options = new DumperOptions();
                options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                options.setWidth(Integer.MAX_VALUE);
                return new Yaml(options).dump(tree);
            }
            return JSON.writeValueAsString(value) + "\n";
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot render the result", e);
        }
    }

    /** A value as a plain map, as the JSON output would show it. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> tree(Object value) {
        return JSON.convertValue(value, Map.class);
    }

    /** Aligned columns: every row padded to the widest cell of each column but the last. */
    public static String columns(List<List<String>> rows) {
        int count = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[count];
        for (List<String> row : rows) {
            for (int c = 0; c < row.size() - 1; c++) {
                widths[c] = Math.max(widths[c], width(row.get(c)));
            }
        }
        StringBuilder text = new StringBuilder();
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < row.size(); c++) {
                String cell = row.get(c);
                cells.add(c == row.size() - 1 ? cell : cell + " ".repeat(widths[c] - width(cell)));
            }
            text.append(String.join("  ", cells).stripTrailing()).append('\n');
        }
        return text.toString();
    }

    /** Terminal columns of a text: East Asian wide characters take two. */
    static int width(String text) {
        int width = 0;
        for (int cp : text.codePoints().toArray()) {
            boolean wide = (cp >= 0x1100 && cp <= 0x115F)
                    || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                    || (cp >= 0xAC00 && cp <= 0xD7A3)
                    || (cp >= 0xF900 && cp <= 0xFAFF)
                    || (cp >= 0xFE30 && cp <= 0xFE4F)
                    || (cp >= 0xFF00 && cp <= 0xFF60)
                    || (cp >= 0xFFE0 && cp <= 0xFFE6);
            width += wide ? 2 : 1;
        }
        return width;
    }
}
