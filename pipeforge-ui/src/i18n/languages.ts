/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

/** Languages of the Web UI; the server localizes its messages to the same ones. */
export const LANGUAGES = ["en-US", "zh-CN"] as const;
export type Language = (typeof LANGUAGES)[number];

/** zh-CN for Chinese browser languages, en-US otherwise. */
export function detectLanguage(browserLanguage: string | undefined): Language {
  return browserLanguage?.toLowerCase().startsWith("zh") ? "zh-CN" : "en-US";
}
