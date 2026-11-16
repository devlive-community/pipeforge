/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.jdbc;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/**
 * How to connect: the URL, the driver and the driver properties. The properties are merged in this
 * order, later ones winning: the dialect's defaults, the user and password of the standard options,
 * and {@code jdbc.properties.*}; a property the dialect controls cannot be set.
 *
 * @param url the JDBC URL
 * @param username the user, or {@code null}
 * @param driver the driver class, or {@code null} to find one for the URL
 * @param properties the driver properties, the password included
 */
public record JdbcConnectionConfig(
        String url, @Nullable String username, @Nullable String driver, Map<String, String> properties)
        implements Serializable {

    private static final Pattern SECRET =
            Pattern.compile("(?i)([^=;&?/]*(?:pass|pwd|secret|token|key)[^=;&]*)=([^;&]*)");
    private static final Pattern USER_INFO = Pattern.compile("//[^/@]*@");

    public JdbcConnectionConfig {
        java.util.Objects.requireNonNull(url, "url");
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /**
     * The connection settings of a node.
     *
     * @throws PipeForgeException {@code PF-CONF-0003} for a property the dialect controls
     */
    public static JdbcConnectionConfig of(ReadonlyConfig config, JdbcDialect dialect, String node) {
        Map<String, String> properties = new LinkedHashMap<>(dialect.defaultProperties());
        String user = config.get(JdbcOptions.USERNAME);
        String password = config.get(JdbcOptions.PASSWORD);
        if (user != null) {
            properties.put("user", user);
        }
        if (password != null) {
            properties.put("password", password);
        }
        Map<String, String> controlled = dialect.controlledProperties();
        for (Map.Entry<String, String> entry :
                config.nativeConfig(JdbcOptions.PROPERTIES).entrySet()) {
            String reason = controlled.get(entry.getKey());
            if (reason != null) {
                throw PipeForgeException.of(ErrorCode.NATIVE_KEY_CONTROLLED)
                        .param("key", JdbcOptions.PROPERTIES + "." + entry.getKey())
                        .param("node", node)
                        .param("reason", reason)
                        .scope(ErrorScope.node(node))
                        .build();
            }
            properties.put(entry.getKey(), entry.getValue());
        }
        return new JdbcConnectionConfig(
                config.get(JdbcOptions.URL), properties.get("user"), config.get(JdbcOptions.DRIVER), properties);
    }

    /** The properties for {@code Driver.connect}. */
    public Properties driverProperties() {
        Properties result = new Properties();
        result.putAll(properties);
        return result;
    }

    /** The URL without credentials, for messages and logs. */
    public String target() {
        return redact(url);
    }

    static String redact(String url) {
        String withoutUser = USER_INFO.matcher(url).replaceAll("//***@");
        return SECRET.matcher(withoutUser).replaceAll("$1=***");
    }

    @Override
    public String toString() {
        Map<String, String> shown = new LinkedHashMap<>(properties);
        shown.computeIfPresent("password", (k, v) -> "***");
        return "JdbcConnectionConfig[" + target() + ", driver=" + driver + ", properties=" + shown + "]";
    }
}
