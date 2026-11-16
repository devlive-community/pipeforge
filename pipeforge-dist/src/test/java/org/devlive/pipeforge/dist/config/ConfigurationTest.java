/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationTest {

    @TempDir
    Path conf;

    static final Map<String, String> TOKEN = Map.of("PIPEFORGE_CLUSTER_TOKEN", "secret");

    Configuration load(Map<String, String> environment, Map<String, String> overrides) {
        return Configuration.load(conf, environment, overrides);
    }

    static void refused(Runnable load, String key, String source, String reason) {
        assertThatThrownBy(load::run).isInstanceOfSatisfying(PipeForgeException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.SETTING_INVALID);
            assertThat(e.params()).containsEntry("key", key).containsEntry("source", source);
            assertThat((String) e.params().get("reason")).contains(reason);
        });
    }

    @Test
    void readsNestedAndDottedKeysFromTheFiles() throws Exception {
        Files.writeString(
                conf.resolve("pipeforge.yaml"),
                """
                cluster:
                  name: east
                  token: from-file
                plugins.dirs: [plugins, more-plugins]
                """);
        Files.writeString(
                conf.resolve("master.yaml"),
                """
                master:
                  rpc.port: 7000
                  heartbeat: { interval: 2s, timeout: 1min }
                scheduler.strategy: pack
                """);
        Files.writeString(
                conf.resolve("worker.yaml"),
                """
                worker:
                  slots: 16
                  labels: { zone: east, net: db-zone }
                  memory.network: 256MB
                network.buffer-timeout: 50ms
                """);
        Configuration config = load(Map.of(), Map.of());
        assertThat(config.string(Settings.CLUSTER_NAME)).isEqualTo("east");
        assertThat(config.string(Settings.CLUSTER_TOKEN)).isEqualTo("from-file");
        assertThat(config.list(Settings.PLUGIN_DIRS)).containsExactly("plugins", "more-plugins");
        assertThat(config.integer(Settings.MASTER_RPC_PORT)).isEqualTo(7000);
        assertThat(config.duration(Settings.HEARTBEAT_INTERVAL)).isEqualTo(Duration.ofSeconds(2));
        assertThat(config.duration(Settings.HEARTBEAT_TIMEOUT)).isEqualTo(Duration.ofMinutes(1));
        assertThat(config.string(Settings.SCHEDULER_STRATEGY)).isEqualTo("pack");
        assertThat(config.integer(Settings.WORKER_SLOTS, 4)).isEqualTo(16);
        assertThat(config.map(Settings.WORKER_LABELS))
                .containsExactly(Map.entry("zone", "east"), Map.entry("net", "db-zone"));
        assertThat(config.size(Settings.NETWORK_MEMORY, 1)).isEqualTo(256L << 20);
        assertThat(config.duration(Settings.BUFFER_TIMEOUT)).isEqualTo(Duration.ofMillis(50));
        assertThat(config.source(Settings.WORKER_SLOTS)).hasValue("conf/worker.yaml");
        assertThat(config.isSet(Settings.WORKER_DIR)).isFalse();
    }

    @Test
    void theEnvironmentOverridesTheFilesAndTheCommandLineOverridesBoth() throws Exception {
        Files.writeString(conf.resolve("worker.yaml"), "worker.slots: 4\nworker.labels: zone=east\n");
        Configuration config = load(
                Map.of(
                        "PIPEFORGE_CLUSTER_TOKEN",
                        "env",
                        "PIPEFORGE_WORKER_SLOTS",
                        "8",
                        "PIPEFORGE_WORKER_LABELS",
                        "zone=west, rack=r1",
                        "UNRELATED",
                        "x"),
                Map.of("worker.slots", "12"));
        assertThat(config.integer(Settings.WORKER_SLOTS, 1)).isEqualTo(12);
        assertThat(config.source(Settings.WORKER_SLOTS)).hasValue("the command line");
        assertThat(config.map(Settings.WORKER_LABELS))
                .containsExactly(Map.entry("zone", "west"), Map.entry("rack", "r1"));
        assertThat(config.source(Settings.WORKER_LABELS)).hasValue("the environment variable PIPEFORGE_WORKER_LABELS");
        assertThat(config.string(Settings.CLUSTER_TOKEN)).isEqualTo("env");
    }

    @Test
    void usesTheDefaultsAndDerivedValuesOfSettingsThatAreNotGiven() {
        Configuration config = load(TOKEN, Map.of());
        assertThat(config.integer(Settings.MASTER_RPC_PORT)).isEqualTo(6123);
        assertThat(config.integer(Settings.WORKER_DATA_PORT)).isEqualTo(6124);
        assertThat(config.string(Settings.WORKER_MASTER)).isEqualTo("localhost:6123");
        assertThat(config.size(Settings.BUFFER_SIZE)).isEqualTo(32 << 10);
        assertThat(config.size(Settings.MAX_ROW_BYTES)).isEqualTo(64 << 20);
        assertThat(config.duration(Settings.SLOT_REQUEST_TIMEOUT)).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.bool(Settings.TLS_ENABLED)).isFalse();
        assertThat(config.list(Settings.PLUGIN_DIRS)).containsExactly("plugins");
        assertThat(config.map(Settings.WORKER_LABELS)).isEmpty();
        assertThat(config.integer(Settings.WORKER_SLOTS, 3)).as("derived").isEqualTo(3);
        assertThat(config.optional(Settings.WORKER_HOST)).isEmpty();
        assertThat(config.optional(Settings.TLS_KEY_STORE)).isEmpty();
        assertThatThrownBy(() -> config.string(Settings.WORKER_HOST)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesUnknownKeysWrongValuesAndAMissingToken() throws Exception {
        refused(() -> load(Map.of(), Map.of()), "cluster.token", "the configuration", "it is required");
        Files.writeString(conf.resolve("worker.yaml"), "worker:\n  slot: 4\n");
        refused(() -> load(TOKEN, Map.of()), "worker.slot", "conf/worker.yaml", "did you mean 'worker.slots'?");
        Files.writeString(conf.resolve("worker.yaml"), "completely.different: 1\n");
        refused(() -> load(TOKEN, Map.of()), "completely.different", "conf/worker.yaml", "there is no such setting");
        Files.delete(conf.resolve("worker.yaml"));
        refused(
                () -> load(TOKEN, Map.of("network.buffer-size", "lots")),
                "network.buffer-size",
                "the command line",
                "not a size");
        refused(
                () -> load(
                        Map.of("PIPEFORGE_CLUSTER_TOKEN", "x", "PIPEFORGE_MASTER_HEARTBEAT_INTERVAL", "soon"),
                        Map.of()),
                "master.heartbeat.interval",
                "the environment variable PIPEFORGE_MASTER_HEARTBEAT_INTERVAL",
                "not a duration");
        refused(
                () -> load(TOKEN, Map.of("worker.slots", "-1")),
                "worker.slots",
                "the command line",
                "must not be negative");
        refused(() -> load(TOKEN, Map.of("worker.slots", "many")), "worker.slots", "the command line", "not a number");
        refused(
                () -> load(TOKEN, Map.of("security.tls.enabled", "yes")),
                "security.tls.enabled",
                "the command line",
                "not true or false");
        refused(
                () -> load(TOKEN, Map.of("worker.master.address", "nowhere")),
                "worker.master.address",
                "the command line",
                "not host:port");
        refused(
                () -> load(TOKEN, Map.of("worker.master.address", "h:70000")),
                "worker.master.address",
                "the command line",
                "outside 1–65535");
        refused(
                () -> load(TOKEN, Map.of("worker.labels", "zone")),
                "worker.labels",
                "the command line",
                "not key=value");
        refused(() -> load(TOKEN, Map.of("no.such", "1")), "no.such", "the command line", "there is no such setting");
        Files.writeString(conf.resolve("master.yaml"), "- a list\n");
        refused(() -> load(TOKEN, Map.of()), "-", "conf/master.yaml", "must hold settings");
        Files.writeString(conf.resolve("master.yaml"), "master: [unclosed\n");
        refused(() -> load(TOKEN, Map.of()), "-", "conf/master.yaml", "cannot be read");
        Files.writeString(conf.resolve("master.yaml"), "");
        assertThat(load(TOKEN, Map.of()).integer(Settings.MASTER_RPC_PORT)).isEqualTo(6123);
    }

    @Test
    void parsesDurationsSizesListsAndMaps() {
        assertThat(Configuration.duration("500ms")).isEqualTo(Duration.ofMillis(500));
        assertThat(Configuration.duration("5MIN")).isEqualTo(Duration.ofMinutes(5));
        assertThat(Configuration.duration("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(Configuration.duration("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(Configuration.duration("1d")).isEqualTo(Duration.ofDays(1));
        assertThat(Configuration.size("512")).isEqualTo(512);
        assertThat(Configuration.size("32KB")).isEqualTo(32 << 10);
        assertThat(Configuration.size("1 gb")).isEqualTo(1L << 30);
        assertThat(Configuration.parse(Settings.PLUGIN_DIRS, "a, b,c", "x")).isEqualTo(List.of("a", "b", "c"));
        assertThat(Configuration.parse(Settings.PLUGIN_DIRS, null, "x")).isEqualTo(List.of());
        assertThat(Configuration.parse(Settings.WORKER_LABELS, null, "x")).isEqualTo(Map.of());
        assertThat(Configuration.parse(Settings.CLUSTER_NAME, null, "x")).isEqualTo("");
        assertThat(Configuration.parse(Settings.WORKER_SLOTS, 4, "x")).isEqualTo(4);
        assertThat(Configuration.similar("worker.slot", "worker.slots")).isTrue();
        assertThat(Configuration.similar("cluster.name", "worker.slots")).isFalse();
    }
}
