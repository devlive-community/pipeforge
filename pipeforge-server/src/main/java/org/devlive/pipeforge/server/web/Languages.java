/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.web;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** The language of messages, from the {@code Accept-Language} header: zh-CN or en-US. */
public final class Languages {

    public static final String ENGLISH = "en-US";
    public static final String CHINESE = "zh-CN";

    private Languages() {}

    /** zh-CN when the first language range is Chinese, en-US otherwise. */
    public static String of(@Nullable String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return ENGLISH;
        }
        String first = acceptLanguage.split(",", 2)[0].trim().toLowerCase(Locale.ROOT);
        return first.startsWith("zh") ? CHINESE : ENGLISH;
    }
}
