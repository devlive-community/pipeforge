/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server settings under {@code pipeforge.*}.
 *
 * @param plugin where plugins are installed
 * @param job defaults for the jobs the server plans
 */
@ConfigurationProperties("pipeforge")
public record ServerProperties(@DefaultValue Plugin plugin, @DefaultValue Job job) {

    /**
     * @param paths directories that hold plugin packages
     * @param cache where zipped plugins are extracted
     * @param dev development mode: plugins loaded from the build output of a source checkout
     */
    public record Plugin(
            @DefaultValue("plugins") List<Path> paths,
            @DefaultValue("work/plugin-cache") Path cache,
            @DefaultValue Dev dev) {
        public Plugin {
            paths = List.copyOf(paths);
        }
    }

    /**
     * @param enabled whether the plugin modules of the source checkout are loaded
     * @param root the root of the checkout; found from the working directory when not set
     */
    public record Dev(@DefaultValue("false") boolean enabled, @Nullable Path root) {}

    /**
     * @param timezone the time zone of jobs that do not set env.timezone
     * @param defaults cluster defaults in the env form, e.g. {@code source.fetch-size}
     */
    public record Job(@DefaultValue("UTC") String timezone, @DefaultValue Map<String, String> defaults) {
        public Job {
            defaults = Map.copyOf(defaults);
        }
    }
}
