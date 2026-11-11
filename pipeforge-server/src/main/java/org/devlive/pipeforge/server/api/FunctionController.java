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
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.expressions.function.FunctionCatalog;
import org.devlive.pipeforge.server.web.Languages;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The built-in functions of the expression language. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "expressions", description = "Expression language and functions")
public class FunctionController {

    private final List<FunctionCatalog.Entry> catalog = FunctionCatalog.load();

    /**
     * A built-in function; texts are in the language of the request.
     *
     * @param category comparison, numeric, string, datetime or conversion
     * @param syntax the special call form, if it has one, e.g. TRIM(... FROM ...)
     * @param nulls how NULL arguments are treated, when it differs from "NULL in, NULL out"
     */
    public record FunctionInfo(
            String name,
            String category,
            @Nullable String syntax,
            boolean deterministic,
            List<String> signatures,
            String description,
            @Nullable String nulls,
            List<ExampleInfo> examples) {}

    /** An example and its result, evaluated when PipeForge is built. */
    public record ExampleInfo(String expression, String result) {}

    @GetMapping("/functions")
    @Operation(
            operationId = "listFunctions",
            summary = "List built-in functions",
            description = "Every built-in function with its signatures, NULL behavior and examples.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "列出内置函数"),
                                @ExtensionProperty(name = "description", value = "每个内置函数的签名、NULL 行为和示例。")
                            }))
    public List<FunctionInfo> functions(
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) @Nullable String accept) {
        String language = Languages.of(accept);
        return catalog.stream()
                .map(e -> new FunctionInfo(
                        e.name(),
                        e.category(),
                        e.syntax(),
                        e.deterministic(),
                        e.signatures(),
                        text(e.description(), language),
                        e.nulls().isEmpty() ? null : text(e.nulls(), language),
                        e.examples().stream()
                                .map(x -> new ExampleInfo(x.expression(), x.result()))
                                .toList()))
                .toList();
    }

    private static String text(Map<String, String> texts, String language) {
        return texts.getOrDefault(language, texts.getOrDefault(Languages.ENGLISH, ""));
    }
}
