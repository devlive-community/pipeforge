/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAPI document is committed as {@code pipeforge-server/openapi.json}; the TypeScript client
 * and the API reference are generated from it. Run with {@code -Dgolden.update=true} to rewrite it
 * after an intended change, then regenerate the client.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiTest {

    static final Path DOCUMENT = Path.of("openapi.json");

    @Autowired
    MockMvc mvc;

    @Test
    void theCommittedDocumentMatchesTheServer() throws Exception {
        String served = mvc.perform(get("/api/v1/openapi.json"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        ObjectMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        JsonNode tree = mapper.readTree(served);
        String pretty = mapper.writeValueAsString(tree) + "\n";
        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(DOCUMENT, pretty, StandardCharsets.UTF_8);
        }
        assertThat(tree.path("openapi").asString()).startsWith("3.");
        assertThat(tree.path("paths").propertyNames())
                .contains(
                        "/api/v1/version",
                        "/api/v1/plugins",
                        "/api/v1/components/{type}/{identifier}",
                        "/api/v1/functions",
                        "/api/v1/errors",
                        "/api/v1/jobs/validate",
                        "/api/v1/explain",
                        "/api/v1/expressions/evaluate");
        assertThat(pretty)
                .as("openapi.json is out of date; run the server tests with -Dgolden.update=true")
                .isEqualTo(Files.readString(DOCUMENT, StandardCharsets.UTF_8));
    }
}
