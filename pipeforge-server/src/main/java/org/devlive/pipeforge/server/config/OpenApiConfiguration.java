/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The OpenAPI document at /api/v1/openapi.json, from which the clients are generated. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    private static final String ERROR = "#/components/schemas/ErrorInfo";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PipeForge API")
                        .version("v1")
                        .description("REST API of the PipeForge server. Every error response is an ErrorInfo"
                                + " object with a registered error code; texts follow Accept-Language (en-US, zh-CN).")
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("/")))
                .components(new Components());
    }

    /** Documents the error responses of every operation with the ErrorInfo schema. */
    @Bean
    public OpenApiCustomizer errorResponses() {
        return api -> {
            // Tags come from bean scanning; sort them so that the document is stable.
            if (api.getTags() != null) {
                api.getTags().sort(java.util.Comparator.comparing(io.swagger.v3.oas.models.tags.Tag::getName));
            }
            Map<String, String> statuses = Map.of(
                    "400", "The request is invalid (PF-CONF-0007)",
                    "422", "The job, expression or plugin options are invalid",
                    "500", "Internal error (PF-SYS-0001)");
            api.getPaths().values().forEach(path -> path.readOperations()
                    .forEach(operation -> statuses.forEach((status, description) -> operation
                            .getResponses()
                            .addApiResponse(
                                    status,
                                    new ApiResponse()
                                            .description(description)
                                            .content(new Content()
                                                    .addMediaType(
                                                            "application/json",
                                                            new MediaType().schema(new Schema<>().$ref(ERROR))))))));
        };
    }
}
