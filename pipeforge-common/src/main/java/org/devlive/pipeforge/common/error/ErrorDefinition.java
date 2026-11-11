/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the error registry: the code, its symbolic name, its kind, and the localized
 * message and hint texts.
 *
 * @param code the code, e.g. {@code PF-CONF-0001}
 * @param name the symbolic name, e.g. {@code OPTION_REQUIRED}
 * @param kind how the engine treats the error
 * @param message message texts by language tag ({@code en-US}, {@code zh-CN})
 * @param hint troubleshooting hints by language tag
 * @param deprecated whether the code is no longer raised (it stays registered forever)
 * @param docs documentation page of the code, relative to the documentation site
 * @param source the registry file the entry comes from
 */
public record ErrorDefinition(
        String code,
        String name,
        ErrorKind kind,
        Map<String, String> message,
        Map<String, String> hint,
        boolean deprecated,
        String docs,
        String source) {

    /** The language used when a text is missing in the requested language. */
    public static final String DEFAULT_LANGUAGE = "en-US";

    /** Languages every entry must provide. */
    public static final Set<String> LANGUAGES = Set.of("en-US", "zh-CN");

    public ErrorDefinition {
        message = Map.copyOf(message);
        hint = Map.copyOf(hint);
    }

    /** Parameters used by the default message, in order of appearance. */
    public Set<String> params() {
        return MessageTemplate.placeholders(message.getOrDefault(DEFAULT_LANGUAGE, ""));
    }

    /** The message in {@code language}, falling back to English. */
    public String message(@Nullable String language) {
        return text(message, language);
    }

    /** The hint in {@code language}, falling back to English. */
    public String hint(@Nullable String language) {
        return text(hint, language);
    }

    private static String text(Map<String, String> texts, @Nullable String language) {
        String text = language == null ? null : texts.get(language);
        return text != null ? text : texts.getOrDefault(DEFAULT_LANGUAGE, "");
    }
}
