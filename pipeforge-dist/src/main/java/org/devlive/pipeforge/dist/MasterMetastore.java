/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.dist.config.Configuration;
import org.devlive.pipeforge.dist.config.Settings;
import org.jspecify.annotations.Nullable;

/**
 * The master's connection to the metastore the server migrates, where it records checkpoints
 * (protocol review, decision D-a). H2 and PostgreSQL drivers ship with the distribution; others,
 * such as MySQL Connector/J, come from {@code master.metastore.driver-dir}.
 */
final class MasterMetastore {

    private MasterMetastore() {}

    /** The URL without credentials, for messages. */
    static String safeUrl(String url) {
        return url.replaceAll("(?i)(password|user)=[^&;]*", "$1=***");
    }

    /**
     * A pool that has connected once; {@code null} without {@code master.metastore.url}.
     *
     * @throws PipeForgeException {@code PF-CLUS-0013} when the database cannot be reached
     */
    static @Nullable HikariDataSource open(Configuration config, Path home) {
        String url = config.optional(Settings.MASTER_METASTORE_URL).orElse("").strip();
        if (url.isEmpty()) {
            return null;
        }
        String user = config.string(Settings.MASTER_METASTORE_USERNAME);
        String password = config.string(Settings.MASTER_METASTORE_PASSWORD);
        HikariConfig pool = new HikariConfig();
        pool.setPoolName("master-metastore");
        pool.setMaximumPoolSize(config.integer(Settings.MASTER_METASTORE_POOL_SIZE));
        String drivers = config.string(Settings.MASTER_METASTORE_DRIVER_DIR).strip();
        if (!drivers.isEmpty()) {
            pool.setDataSource(new DriverDataSource(driver(home.resolve(drivers), url), url, user, password));
        } else {
            pool.setJdbcUrl(url);
            pool.setUsername(user);
            pool.setPassword(password);
        }
        try {
            return new HikariDataSource(pool);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw unavailable(url, String.valueOf(cause.getMessage()), cause);
        }
    }

    /** The driver of {@code dir} that accepts the URL. */
    static Driver driver(Path dir, String url) {
        List<URL> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path jar :
                    files.filter(f -> f.toString().endsWith(".jar")).sorted().toList()) {
                jars.add(jar.toUri().toURL());
            }
        } catch (IOException e) {
            throw unavailable(url, "the driver directory " + dir + " cannot be read: " + e.getMessage(), e);
        }
        // lives as long as the pool, whose connections use the driver's classes
        @SuppressWarnings("resource")
        URLClassLoader loader =
                new URLClassLoader("metastore-drivers", jars.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        for (Driver candidate : ServiceLoader.load(Driver.class, loader)) {
            try {
                if (candidate.acceptsURL(url)) {
                    return candidate;
                }
            } catch (SQLException e) {
                // this driver cannot tell; try the next
            }
        }
        throw unavailable(url, "no driver in " + dir + " accepts the URL", null);
    }

    private static PipeForgeException unavailable(String url, String reason, @Nullable Throwable cause) {
        return PipeForgeException.of(ErrorCode.METASTORE_UNAVAILABLE)
                .param("url", safeUrl(url))
                .param("reason", reason)
                .cause(cause)
                .build();
    }

    /** Connections from one driver, for a driver the class path does not have. */
    static final class DriverDataSource implements DataSource {
        private final Driver driver;
        private final String url;
        private final Properties properties = new Properties();

        DriverDataSource(Driver driver, String url, String user, String password) {
            this.driver = driver;
            this.url = url;
            if (!user.isEmpty()) {
                properties.setProperty("user", user);
            }
            if (!password.isEmpty()) {
                properties.setProperty("password", password);
            }
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = driver.connect(url, properties);
            if (connection == null) {
                throw new SQLException("the driver does not accept " + safeUrl(url));
            }
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Properties own = new Properties();
            own.setProperty("user", username);
            own.setProperty("password", password);
            return driver.connect(url, own);
        }

        @Override
        public @Nullable PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }
}
