/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.metastore;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Migration scripts are only ever added (design 23, section 6.3): every script is listed in {@code
 * metastore-scripts.sha256} with its checksum, and a listed script never changes or disappears.
 */
class MetastoreScriptsTest {

    static final Path RESOURCES = Path.of("src/main/resources");
    static final Path LIST = Path.of("metastore-scripts.sha256");

    /** The scripts on disk, relative to the resources, with their SHA-256. */
    static Map<String, String> scripts() throws Exception {
        Map<String, String> scripts = new TreeMap<>();
        try (Stream<Path> files = Files.walk(RESOURCES.resolve("db/metastore"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
                scripts.put(
                        RESOURCES.relativize(file).toString().replace('\\', '/'),
                        HexFormat.of().formatHex(digest));
            }
        }
        return scripts;
    }

    static Map<String, String> listed() throws IOException {
        Map<String, String> listed = new TreeMap<>();
        for (String line : Files.readAllLines(LIST)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+", 2);
            listed.put(parts[1], parts[0]);
        }
        return listed;
    }

    @Test
    void listedScriptsNeverChangeOrDisappear() throws Exception {
        Map<String, String> scripts = scripts();
        listed().forEach((script, sha) -> {
            assertThat(scripts).as("%s was released and must stay", script).containsKey(script);
            assertThat(scripts.get(script))
                    .as("%s was released and must not change; add a new migration instead", script)
                    .isEqualTo(sha);
        });
    }

    @Test
    void everyScriptIsListed() throws Exception {
        Set<String> unlisted = new TreeSet<>(scripts().keySet());
        unlisted.removeAll(listed().keySet());
        assertThat(unlisted)
                .as("add new scripts to %s with: shasum -a 256 <script>", LIST)
                .isEmpty();
    }

    /** What an expand script may do: create tables and indexes, add columns the previous version can leave empty. */
    static final List<java.util.regex.Pattern> EXPAND = List.of(
            java.util.regex.Pattern.compile("CREATE TABLE \\w+ \\(.*\\)( ENGINE=.*)?", java.util.regex.Pattern.DOTALL),
            java.util.regex.Pattern.compile("CREATE (UNIQUE )?INDEX \\w+ ON \\w+ \\([\\w, ]+\\)"),
            java.util.regex.Pattern.compile(
                    "ALTER TABLE \\w+ ADD COLUMN \\w+ [A-Z]+(\\(\\d+\\))?( DEFAULT \\S+( NOT NULL)?)?"));

    /** The statements of a script, without comments and with single spaces. */
    static List<String> statements(String script) {
        StringBuilder text = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            text.append(line.replaceFirst("--.*", "")).append(' ');
        }
        List<String> statements = new java.util.ArrayList<>();
        for (String statement : text.toString().split(";", -1)) {
            String normalized =
                    statement.strip().replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
            if (!normalized.isEmpty()) {
                statements.add(normalized);
            }
        }
        return statements;
    }

    static boolean isExpansion(String statement) {
        return EXPAND.stream().anyMatch(p -> p.matcher(statement).matches());
    }

    @Test
    void expandScriptsOnlyAdd() throws Exception {
        for (String script : scripts().keySet()) {
            if (!script.contains("/expand/")) {
                continue;
            }
            for (String statement : statements(Files.readString(RESOURCES.resolve(script)))) {
                assertThat(isExpansion(statement))
                        .as(
                                "%s: '%s' is not an expansion; drops, renames, type changes and NOT NULL columns"
                                        + " without a default belong in a contract script",
                                script, statement)
                        .isTrue();
            }
        }
    }

    @Test
    void recognizesWhatIsNotAnExpansion() {
        assertThat(statements(
                        "CREATE TABLE t (\n  a INT NOT NULL, -- why\n  PRIMARY KEY (a)\n);\nCREATE INDEX i ON t (a);"))
                .allMatch(MetastoreScriptsTest::isExpansion)
                .hasSize(2);
        assertThat(isExpansion("ALTER TABLE t ADD COLUMN c VARCHAR(10)")).isTrue();
        assertThat(isExpansion("ALTER TABLE t ADD COLUMN c INT DEFAULT 0 NOT NULL"))
                .isTrue();
        assertThat(isExpansion("ALTER TABLE t ADD COLUMN c INT NOT NULL")).isFalse();
        assertThat(isExpansion("ALTER TABLE t DROP COLUMN c")).isFalse();
        assertThat(isExpansion("ALTER TABLE t RENAME COLUMN c TO d")).isFalse();
        assertThat(isExpansion("ALTER TABLE t ALTER COLUMN c TYPE BIGINT")).isFalse();
        assertThat(isExpansion("ALTER TABLE t MODIFY c BIGINT")).isFalse();
        assertThat(isExpansion("DROP TABLE t")).isFalse();
        assertThat(isExpansion("UPDATE t SET c = 1")).isFalse();
    }

    @Test
    void everyDatabaseHasTheSameMigrations() throws Exception {
        for (String step : List.of("expand", "contract")) {
            Map<String, Set<String>> byVendor = new TreeMap<>();
            for (MetastoreVendor vendor : MetastoreVendor.values()) {
                byVendor.put(vendor.folder(), names(RESOURCES.resolve("db/metastore/" + step + "/" + vendor.folder())));
            }
            assertThat(byVendor.values().stream().distinct())
                    .as("the %s scripts of every database: %s", step, byVendor)
                    .hasSizeLessThanOrEqualTo(1);
        }
        assertThat(names(RESOURCES.resolve("db/metastore/expand/h2"))).contains("V1__runs_and_workers.sql");
    }

    private static Set<String> names(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> String.valueOf(f.getFileName())).collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
