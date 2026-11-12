/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * pipeforge-cli/cli.json, which the documentation site renders as the CLI reference, matches the
 * command definitions; -Dpipeforge.cli.update=true rewrites it.
 */
class CliReferenceTest {

    static final Path FILE = Path.of("cli.json");

    @Test
    void theCommittedReferenceMatchesTheCommands() throws Exception {
        String rendered =
                new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(CliReference.render())
                        + "\n";
        if (Boolean.getBoolean("pipeforge.cli.update") || !Files.exists(FILE)) {
            Files.writeString(FILE, rendered, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(FILE, StandardCharsets.UTF_8))
                .as("cli.json is stale; run the test with -Dpipeforge.cli.update=true")
                .isEqualTo(rendered);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCommandIsDocumentedInBothLanguages() {
        Map<String, Object> reference = CliReference.render();
        List<Map<String, Object>> commands = (List<Map<String, Object>>) reference.get("commands");
        assertThat(commands)
                .extracting(c -> c.get("name"))
                .containsExactly(
                        "validate",
                        "explain",
                        "run",
                        "expr",
                        "expr eval",
                        "ui",
                        "version",
                        "doctor",
                        "completion",
                        "plugin",
                        "plugin validate",
                        "plugin test",
                        "plugin docs");
        for (Map<String, Object> command : commands) {
            Map<String, String> description = (Map<String, String>) command.get("description");
            assertThat(description.get("en-US")).as("%s", command.get("name")).isNotBlank();
            assertThat(description.get("zh-CN")).as("%s", command.get("name")).isNotEqualTo(description.get("en-US"));
        }
        assertThat((List<Object>) reference.get("exitCodes")).hasSize(10);
        assertThat((List<Map<String, Object>>) reference.get("globalOptions"))
                .extracting(o -> ((List<String>) o.get("names")).get(0))
                .containsExactly("-o", "--lang", "--no-color", "-q", "-v", "--plugin-path", "--dev");
    }
}
