/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The settings, the configuration files of the distribution and the generated reference agree. */
class SettingsTest {

    static final Path CONF = Path.of("src", "main", "dist", "conf");
    static final Path REFERENCE = Path.of("configuration.json");

    @Test
    void everySettingIsDescribedAndListedInItsFile() throws Exception {
        Set<String> variables = new HashSet<>();
        for (Setting setting : Settings.all()) {
            assertThat(setting.description()).containsOnlyKeys("en-US", "zh-CN");
            assertThat(variables.add(setting.environmentVariable()))
                    .as(setting.key())
                    .isTrue();
            assertThat(setting.environmentVariable()).matches("PIPEFORGE_[A-Z_]+");
            String file = Files.readString(CONF.resolve(setting.file() + ".yaml"), StandardCharsets.UTF_8);
            String line = "# " + setting.key() + ":";
            assertThat(file)
                    .as("conf/%s.yaml lists %s", setting.file(), setting.key())
                    .contains(line);
            if (setting.defaultValue() != null && !setting.defaultValue().isEmpty()) {
                assertThat(file).contains(line + " " + setting.defaultValue());
            }
            // the default parses as a value of its type
            if (setting.defaultValue() != null) {
                Configuration.parse(setting, setting.defaultValue(), null);
            }
        }
        assertThat(Settings.find("worker.slots")).hasValue(Settings.WORKER_SLOTS);
        assertThat(Settings.WORKER_SLOTS.environmentVariable()).isEqualTo("PIPEFORGE_WORKER_SLOTS");
        assertThat(Settings.BUFFERS_PER_CHANNEL.environmentVariable())
                .isEqualTo("PIPEFORGE_NETWORK_BUFFERS_PER_CHANNEL");
        // the files of the distribution load, with a token
        assertThat(Configuration.load(CONF, Map.of("PIPEFORGE_CLUSTER_TOKEN", "t"), Map.of())
                        .integer(Settings.MASTER_RPC_PORT))
                .isEqualTo(6123);
    }

    /** -Dpipeforge.configuration.update=true rewrites the reference. */
    @Test
    void theCommittedReferenceMatchesTheSettings() throws Exception {
        String current = ConfigurationReference.json();
        if (Boolean.getBoolean("pipeforge.configuration.update") || !Files.exists(REFERENCE)) {
            Files.writeString(REFERENCE, current, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(REFERENCE, StandardCharsets.UTF_8))
                .as("configuration.json is stale; run the test with -Dpipeforge.configuration.update=true")
                .isEqualTo(current);
        assertThat(current).contains("\"key\" : \"worker.slots\"").contains("\"env\" : \"PIPEFORGE_WORKER_SLOTS\"");
    }
}
