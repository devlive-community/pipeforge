/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.config;

import java.nio.file.Path;
import java.time.ZoneId;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.expressions.ExpressionEngine;
import org.devlive.pipeforge.plugin.PluginManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The engine components the API uses. */
@Configuration(proxyBeanMethods = false)
public class ServerConfiguration {

    /** Installed plugins, loaded once at start-up; plugin class loaders are closed with the server. */
    @Bean(destroyMethod = "close")
    public PluginManager pluginManager(ServerProperties properties) {
        return PluginManager.load(PluginScan.scan(properties.plugin(), Path.of("")), PluginManager.Options.defaults());
    }

    @Bean
    public ExpressionEngine expressionEngine() {
        return new ExpressionEngine();
    }

    /** Defaults applied to the jobs the server plans. */
    @Bean
    public JobDefaults jobDefaults(ServerProperties properties) {
        return new JobDefaults(
                ZoneId.of(properties.job().timezone()),
                ReadonlyConfig.of(properties.job().defaults()));
    }

    /**
     * Job defaults resolved from the configuration.
     *
     * @param timeZone the time zone of jobs without env.timezone
     * @param cluster cluster defaults in the env form
     */
    public record JobDefaults(ZoneId timeZone, ReadonlyConfig cluster) {}
}
