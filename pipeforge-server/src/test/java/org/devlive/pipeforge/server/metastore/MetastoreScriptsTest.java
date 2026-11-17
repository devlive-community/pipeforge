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
