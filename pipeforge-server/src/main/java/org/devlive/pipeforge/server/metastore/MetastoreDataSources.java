/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.metastore;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.server.config.ServerProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** The connection pool of the metastore. */
public final class MetastoreDataSources {

    private MetastoreDataSources() {}

    /**
     * A pool that has connected once, so that a wrong URL or password stops the server at once.
     *
     * @throws PipeForgeException {@code PF-CLUS-0013} when the database cannot be reached
     */
    public static HikariDataSource create(ServerProperties.Metastore settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("metastore");
        config.setMaximumPoolSize(settings.poolSize());
        Driver driver = driver(settings);
        if (driver != null) {
            SimpleDriverDataSource direct = new SimpleDriverDataSource(driver, settings.url());
            direct.setUsername(settings.username());
            direct.setPassword(settings.password());
            config.setDataSource(direct);
        } else {
            config.setJdbcUrl(settings.url());
            config.setUsername(settings.username());
            config.setPassword(settings.password());
        }
        try {
            return new HikariDataSource(config);
        } catch (RuntimeException e) {
            throw unavailable(settings, e.getCause() != null ? e.getCause() : e);
        }
    }

    /** The driver of {@code driver-dir} that accepts the URL; none without a directory. */
    static @Nullable Driver driver(ServerProperties.Metastore settings) {
        Path dir = settings.driverDir();
        if (dir == null) {
            return null;
        }
        List<URL> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path jar :
                    files.filter(f -> f.toString().endsWith(".jar")).sorted().toList()) {
                jars.add(jar.toUri().toURL());
            }
        } catch (IOException e) {
            throw unavailable(settings, e);
        }
        // only the drivers of the directory, not those the server ships; the loader lives as long as
        // the pool, whose connections use the driver's classes
        @SuppressWarnings("resource")
        URLClassLoader loader =
                new URLClassLoader("metastore-drivers", jars.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        for (Driver candidate : ServiceLoader.load(Driver.class, loader)) {
            try {
                if (candidate.acceptsURL(settings.url())) {
                    return candidate;
                }
            } catch (SQLException e) {
                // this driver cannot tell; try the next
            }
        }
        throw PipeForgeException.of(ErrorCode.METASTORE_UNAVAILABLE)
                .param("url", settings.safeUrl())
                .param("reason", "no driver in " + dir + " accepts the URL")
                .build();
    }

    private static PipeForgeException unavailable(ServerProperties.Metastore settings, Throwable cause) {
        return PipeForgeException.of(ErrorCode.METASTORE_UNAVAILABLE)
                .param("url", settings.safeUrl())
                .param("reason", String.valueOf(cause.getMessage()))
                .cause(cause)
                .build();
    }
}
