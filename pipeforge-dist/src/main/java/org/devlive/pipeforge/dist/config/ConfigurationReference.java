/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The configuration reference of the documentation: every setting with its file, type, default,
 * environment variable and description. A test keeps {@code pipeforge-dist/configuration.json}
 * equal to it; the documentation site renders that file.
 */
public final class ConfigurationReference {

    private ConfigurationReference() {}

    public static String json() {
        List<Map<String, Object>> settings = new ArrayList<>();
        for (Setting setting : Settings.all()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", setting.key());
            entry.put("file", "conf/" + setting.file() + ".yaml");
            entry.put("type", setting.type().name().toLowerCase(Locale.ROOT));
            entry.put("default", setting.defaultValue());
            entry.put("derived", setting.derived() == null ? null : new java.util.TreeMap<>(setting.derived()));
            entry.put("required", setting.required());
            entry.put("env", setting.environmentVariable());
            entry.put("description", new java.util.TreeMap<>(setting.description()));
            settings.add(entry);
        }
        try {
            return new ObjectMapper()
                            .enable(SerializationFeature.INDENT_OUTPUT)
                            .writeValueAsString(Map.of("settings", settings))
                    + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
