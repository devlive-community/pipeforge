/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { THEME_STORAGE_KEY } from "@pipeforge/design-tokens";
import { create } from "zustand";
import { detectLanguage, LANGUAGES, type Language } from "@/i18n/languages";

/** Theme modes; "system" follows prefers-color-scheme. */
export type ThemeMode = "light" | "dark" | "system";

/** Key in localStorage that stores the chosen language. */
export const LANGUAGE_STORAGE_KEY = "pipeforge-language";

export interface Preferences {
  theme: ThemeMode;
  language: Language;
  setTheme: (mode: ThemeMode) => void;
  setLanguage: (language: Language) => void;
}

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Private windows may refuse storage; the choice then lasts for this page only.
  }
}

function systemPrefersDark(): boolean {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-color-scheme: dark)").matches;
}

/** Applies a theme mode to the document: the .dark class drives the tokens. */
export function applyTheme(mode: ThemeMode) {
  const dark = mode === "dark" || (mode === "system" && systemPrefersDark());
  document.documentElement.classList.toggle("dark", dark);
  document.documentElement.dataset.themeMode = mode;
}

function initialTheme(): ThemeMode {
  const stored = read(THEME_STORAGE_KEY);
  return stored === "light" || stored === "dark" ? stored : "system";
}

function initialLanguage(): Language {
  const stored = read(LANGUAGE_STORAGE_KEY);
  return (LANGUAGES as readonly string[]).includes(stored ?? "")
    ? (stored as Language)
    : detectLanguage(navigator.language);
}

/** Theme and language, kept in localStorage and shared by every page. */
export const usePreferences = create<Preferences>((set) => ({
  theme: initialTheme(),
  language: initialLanguage(),
  setTheme: (theme) => {
    write(THEME_STORAGE_KEY, theme);
    applyTheme(theme);
    set({ theme });
  },
  setLanguage: (language) => {
    write(LANGUAGE_STORAGE_KEY, language);
    document.documentElement.lang = language;
    set({ language });
  },
}));
