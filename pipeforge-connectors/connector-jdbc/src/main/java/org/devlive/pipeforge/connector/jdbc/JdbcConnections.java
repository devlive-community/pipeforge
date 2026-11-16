/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ServiceLoader;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * Opens connections with the driver of the plugin. The driver is looked up in the plugin's class
 * loader, by the configured class or among the drivers that accept the URL, so that drivers of
 * different plugins do not meet in {@link DriverManager}.
 */
public final class JdbcConnections {

    private JdbcConnections() {}

    /**
     * A new connection.
     *
     * @throws PipeForgeException {@code PF-PLUG-0003} without a driver, a connection or login error
     *     otherwise
     */
    public static Connection open(JdbcConnectionConfig config, JdbcDialect dialect, ClassLoader loader, String node) {
        Driver driver = driver(config, dialect, loader);
        try {
            Connection connection = driver.connect(config.url(), config.driverProperties());
            if (connection == null) {
                throw missing(dialect, config);
            }
            return connection;
        } catch (SQLException e) {
            throw JdbcErrors.of(e, dialect, config, true, null, node, true);
        }
    }

    static Driver driver(JdbcConnectionConfig config, JdbcDialect dialect, ClassLoader loader) {
        String name = config.driver();
        if (name != null) {
            try {
                return Class.forName(name, true, loader)
                        .asSubclass(Driver.class)
                        .getDeclaredConstructor()
                        .newInstance();
            } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
                throw PipeForgeException.of(ErrorCode.DRIVER_MISSING)
                        .param("plugin", dialect.name())
                        .param("driver", name)
                        .cause(e)
                        .build();
            }
        }
        for (Driver candidate : ServiceLoader.load(Driver.class, loader)) {
            try {
                if (candidate.acceptsURL(config.url())) {
                    return candidate;
                }
            } catch (SQLException e) {
                // this driver cannot tell; try the next
            }
        }
        try {
            return DriverManager.getDriver(config.url());
        } catch (SQLException e) {
            throw missing(dialect, config);
        }
    }

    private static PipeForgeException missing(JdbcDialect dialect, JdbcConnectionConfig config) {
        String url = config.url();
        int second = url.indexOf(':', url.indexOf(':') + 1);
        return PipeForgeException.of(ErrorCode.DRIVER_MISSING)
                .param("plugin", dialect.name())
                .param("driver", "a JDBC driver for " + (second > 0 ? url.substring(0, second) : config.target()))
                .build();
    }
}
