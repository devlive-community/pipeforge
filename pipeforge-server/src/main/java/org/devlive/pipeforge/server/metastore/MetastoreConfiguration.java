/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.metastore;

import com.zaxxer.hikari.HikariDataSource;
import org.devlive.pipeforge.server.config.ServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The metastore: its connection pool, and its schema brought up to date when the server starts. */
@Configuration(proxyBeanMethods = false)
public class MetastoreConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MetastoreConfiguration.class);

    @Bean(destroyMethod = "close")
    HikariDataSource metastoreDataSource(ServerProperties properties) {
        MetastoreVendor.of(properties.metastore().url());
        return MetastoreDataSources.create(properties.metastore());
    }

    @Bean
    MetastoreMigrations metastoreMigrations(HikariDataSource metastoreDataSource, ServerProperties properties) {
        ServerProperties.Metastore settings = properties.metastore();
        MetastoreMigrations migrations =
                new MetastoreMigrations(metastoreDataSource, MetastoreVendor.of(settings.url()), settings.safeUrl());
        if (settings.migrate()) {
            MetastoreMigrations.Result result = migrations.migrate();
            LOG.info(
                    "Metastore {} ({}) is at version {}; {} migration(s) applied",
                    settings.safeUrl(),
                    migrations.vendor().folder(),
                    result.version(),
                    result.applied());
        } else {
            LOG.info(
                    "Metastore {} is at version {}; migrations are off and {} are pending",
                    settings.safeUrl(),
                    migrations.version(),
                    migrations.pending().size());
        }
        return migrations;
    }
}
