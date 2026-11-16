/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist.config;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One setting of the cluster processes.
 *
 * @param key the dotted key, e.g. {@code worker.slots}
 * @param file the configuration file it belongs in: {@code pipeforge}, {@code master} or {@code worker}
 * @param type how its value is written
 * @param defaultValue the default as written in a file, or {@code null} when it has none or it is derived
 * @param derived how the default is derived, in both languages, or {@code null}
 * @param required whether the processes refuse to start without it
 * @param description what it does, in {@code en-US} and {@code zh-CN}
 */
public record Setting(
        String key,
        String file,
        Type type,
        @Nullable String defaultValue,
        @Nullable Map<String, String> derived,
        boolean required,
        Map<String, String> description) {

    /** How values are written. */
    public enum Type {
        STRING,
        INT,
        BOOLEAN,
        /** {@code 500ms}, {@code 30s}, {@code 5min}, {@code 2h} */
        DURATION,
        /** {@code 512}, {@code 32KB}, {@code 64MB}, {@code 1GB} */
        SIZE,
        /** A YAML list or {@code a,b,c} */
        LIST,
        /** A YAML map or {@code key=value,key=value} */
        MAP,
        /** {@code host:port} */
        ADDRESS
    }

    public Setting {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(type, "type");
        description = Map.copyOf(description);
        derived = derived == null ? null : Map.copyOf(derived);
    }

    /** The environment variable that overrides it: {@code worker.slots} → {@code PIPEFORGE_WORKER_SLOTS}. */
    public String environmentVariable() {
        return "PIPEFORGE_" + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
