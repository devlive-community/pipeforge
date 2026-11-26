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
 * @param metastore the database the server keeps its records in
 * @param master the master the server submits runs to
 */
@ConfigurationProperties("pipeforge")
public record ServerProperties(
        @DefaultValue Plugin plugin,
        @DefaultValue Job job,
        @DefaultValue Metastore metastore,
        @DefaultValue Master master) {

    /**
     * @param address {@code host:port} of the master's RPC port, or a comma-separated list of the masters of a
     *     cluster with standbys, whose leader the server follows; without it runs cannot be submitted
     * @param token the cluster token ({@code cluster.token} of the cluster)
     * @param tls the TLS settings of the cluster, when it uses TLS
     * @param pollInterval how often the server asks the master about the runs that have not ended
     * @param metricsInterval how often the server keeps a sample of a running run's metrics
     */
    public record Master(
            @Nullable String address,
            @DefaultValue("") String token,
            @DefaultValue Tls tls,
            @DefaultValue("1s") java.time.Duration pollInterval,
            @DefaultValue("10s") java.time.Duration metricsInterval) {}

    /**
     * @param enabled whether the cluster uses TLS
     * @param keyStore the server's key store (PKCS#12)
     * @param keyStorePassword its password
     * @param trustStore the store of the certificates the server trusts
     * @param trustStorePassword its password
     */
    public record Tls(
            @DefaultValue("false") boolean enabled,
            @Nullable Path keyStore,
            @DefaultValue("") String keyStorePassword,
            @Nullable Path trustStore,
            @DefaultValue("") String trustStorePassword) {}

    /**
     * @param url the JDBC URL: H2, MySQL or PostgreSQL
     * @param username the user
     * @param password the password
     * @param driverDir a directory of JDBC driver jars, for a database whose driver the server does
     *     not ship (MySQL); the driver that accepts the URL is used
     * @param poolSize the most connections the server opens
     * @param migrate whether the server applies pending expand migrations when it starts
     * @param metricsRetention how long samples of run metrics are kept
     */
    public record Metastore(
            @DefaultValue("jdbc:h2:mem:pipeforge;DB_CLOSE_DELAY=-1") String url,
            @Nullable String username,
            @Nullable String password,
            @Nullable Path driverDir,
            @DefaultValue("10") int poolSize,
            @DefaultValue("true") boolean migrate,
            @DefaultValue("30d") java.time.Duration metricsRetention) {

        /** The URL without user information, for messages and logs. */
        public String safeUrl() {
            int query = url.indexOf('?');
            return query < 0 ? url : url.substring(0, query);
        }
    }

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
