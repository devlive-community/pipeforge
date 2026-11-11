/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useCallback } from "react";
import { usePreferences } from "@/state/preferences";
import en from "./en-US.json";
import { type Language } from "./languages";
import zh from "./zh-CN.json";

export { detectLanguage, LANGUAGES, type Language } from "./languages";

type Dictionary = typeof en;
type Leaves<T, P extends string = ""> = {
  [K in keyof T & string]: T[K] extends string ? `${P}${K}` : Leaves<T[K], `${P}${K}.`>;
}[keyof T & string];

/** Every message key, e.g. "overview.title"; a typo is a type error. */
export type MessageKey = Leaves<Dictionary>;

/** The dictionaries; the Chinese one must have the same shape as the English one. */
export const DICTIONARIES: Record<Language, Dictionary> = { "en-US": en, "zh-CN": zh satisfies Dictionary };

/** The message for `key` with {name} placeholders replaced by `params`. */
export function translate(language: Language, key: MessageKey, params?: Record<string, string | number>): string {
  let value: unknown = DICTIONARIES[language];
  for (const part of key.split(".")) {
    value = (value as Record<string, unknown> | undefined)?.[part];
  }
  const text = typeof value === "string" ? value : key;
  return params ? text.replace(/\{(\w+)\}/g, (match, name: string) => String(params[name] ?? match)) : text;
}

/** The translate function for the current language. */
export function useT() {
  const language = usePreferences((s) => s.language);
  return useCallback(
    (key: MessageKey, params?: Record<string, string | number>) => translate(language, key, params),
    [language],
  );
}
