/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** What the end-to-end tests and the benchmark share: run states, the driver plugin, query results. */
final class Support {

    private Support() {}

    static boolean ended(JsonNode detail) {
        return Set.of("SUCCESS", "FAILED", "CANCELLED", "TIMEOUT")
                .contains(detail.path("run").path("status").asText());
    }

    /** The mysql-connector-j driver plugin, from the Connector/J jar of the tests. */
    static void installDriverPlugin(Path plugins) throws Exception {
        Path jar = Path.of(com.mysql.cj.jdbc.Driver.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path plugin = plugins.resolve("mysql-connector-j");
        Files.createDirectories(plugin.resolve("lib"));
        Files.copy(jar, plugin.resolve("lib").resolve(String.valueOf(jar.getFileName())));
        Files.writeString(
                plugin.resolve("plugin.yaml"),
                """
                id: mysql-connector-j
                name: MySQL Connector/J
                version: 8.4.0
                api-version: "0.x"
                provides:
                  - { type: driver, identifier: mysql-connector-j, class: com.mysql.cj.jdbc.Driver }
                """);
    }

    /** Every row of a query, each value as text: numbers without trailing zeros, times to the millisecond. */
    static List<List<String>> rows(Connection connection, String sql) throws Exception {
        try (connection;
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            ResultSetMetaData meta = result.getMetaData();
            List<List<String>> rows = new ArrayList<>();
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    Object value = result.getObject(i);
                    row.add(text(value));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    static String text(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            return new java.math.BigDecimal(number.toString())
                    .stripTrailingZeros()
                    .toPlainString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.time.LocalDateTime time) {
            return time.toString();
        }
        return value.toString();
    }

    static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
