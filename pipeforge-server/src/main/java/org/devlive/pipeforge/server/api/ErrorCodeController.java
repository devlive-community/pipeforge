/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import org.devlive.pipeforge.common.error.ErrorDefinition;
import org.devlive.pipeforge.common.error.ErrorRegistry;
import org.devlive.pipeforge.server.web.Languages;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The registered error codes, including those of loaded plugins. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "errors", description = "Error code registry")
public class ErrorCodeController {

    /**
     * A registered error code; message and hint are templates with {param} placeholders, in the
     * language of the request.
     *
     * @param category the category part of the code, e.g. CONF
     * @param kind how the engine reacts: FATAL, TRANSIENT or DATA
     */
    public record ErrorCodeInfo(
            String code,
            String name,
            String category,
            String kind,
            boolean deprecated,
            List<String> params,
            String message,
            String hint) {}

    @GetMapping("/errors")
    @Operation(
            operationId = "listErrorCodes",
            summary = "List error codes",
            description = "Every registered error code with its message and hint templates.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "列出错误码"),
                                @ExtensionProperty(name = "description", value = "每个已登记的错误码及其消息和提示模板。")
                            }))
    public List<ErrorCodeInfo> errors(
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) @Nullable String accept) {
        String language = Languages.of(accept);
        return ErrorRegistry.global().all().stream()
                .sorted(Comparator.comparing(ErrorDefinition::code))
                .map(d -> new ErrorCodeInfo(
                        d.code(),
                        d.name(),
                        d.code().split("-", 3)[1],
                        d.kind().name(),
                        d.deprecated(),
                        d.params().stream().sorted().toList(),
                        d.message(language),
                        d.hint(language)))
                .toList();
    }
}
