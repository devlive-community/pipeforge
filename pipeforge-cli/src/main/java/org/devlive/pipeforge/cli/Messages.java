/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.jspecify.annotations.Nullable;

/**
 * The texts of the command in English and Chinese: help, headings and results. Error messages come
 * from the error registry instead, the same as in the Web UI and the API.
 */
public final class Messages {

    /** The bundle with help texts and messages. */
    public static final String BUNDLE = "org.devlive.pipeforge.cli.messages";

    /** The languages of the texts. */
    public static final List<String> LANGUAGES = List.of("en-US", "zh-CN");

    private final String language;
    private final ResourceBundle bundle;

    private Messages(String language) {
        this.language = language;
        this.bundle = bundle(language);
    }

    public static Messages of(String language) {
        return new Messages(language);
    }

    /** The bundle of a language; English is the base bundle, so no system locale is mixed in. */
    public static ResourceBundle bundle(String language) {
        Locale locale = language.equals("zh-CN") ? Locale.SIMPLIFIED_CHINESE : Locale.ROOT;
        return ResourceBundle.getBundle(
                BUNDLE, locale, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /**
     * The language to use: the one asked for, then PIPEFORGE_LANG, then Chinese for a Chinese system
     * locale, else English.
     */
    public static String resolve(@Nullable String asked, @Nullable String variable, Locale system) {
        for (String candidate : new String[] {asked, variable}) {
            String normalized = normalize(candidate);
            if (normalized != null) {
                return normalized;
            }
        }
        return system.getLanguage().equals("zh") ? "zh-CN" : "en-US";
    }

    /** {@code zh}, {@code zh_CN}, {@code zh-CN} become zh-CN; {@code en*} en-US; others null. */
    static @Nullable String normalize(@Nullable String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String lower = language.toLowerCase(Locale.ROOT);
        if (lower.startsWith("zh")) {
            return "zh-CN";
        }
        if (lower.startsWith("en")) {
            return "en-US";
        }
        return null;
    }

    public String language() {
        return language;
    }

    /** A message; patterns follow {@link MessageFormat}, so an apostrophe is written twice. */
    public String get(String key, Object... arguments) {
        return new MessageFormat(bundle.getString(key), Locale.ROOT).format(arguments);
    }
}
