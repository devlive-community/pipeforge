/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

/** Key in localStorage that stores the chosen theme mode. */
export const THEME_STORAGE_KEY = "pipeforge-theme";

/** Theme modes offered by the UI and the documentation site. */
export const THEME_MODES = Object.freeze(["light", "dark", "system"]);

/**
 * Inline script for <head> that applies the theme before the first frame, so the page never
 * flashes the wrong theme. "system" (the default) follows prefers-color-scheme.
 */
export const themeInitScript = `(function(){try{var m=localStorage.getItem(${JSON.stringify(
  THEME_STORAGE_KEY,
)});if(m!=="light"&&m!=="dark")m="system";var d=m==="dark"||(m==="system"&&window.matchMedia("(prefers-color-scheme: dark)").matches);var r=document.documentElement;r.classList.toggle("dark",d);r.dataset.themeMode=m;}catch(e){}})();`;

/**
 * Text and background pairs that carry body text. Each pair must reach a WCAG contrast
 * ratio of at least 4.5:1 in both themes (checked by the package tests).
 */
export const TEXT_CONTRAST_PAIRS = Object.freeze([
  ["fg", "surface"],
  ["fg", "surface-2"],
  ["fg", "surface-3"],
  ["fg-muted", "surface"],
  ["fg-muted", "surface-2"],
  ["fg-subtle", "surface"],
  ["fg-subtle", "surface-2"],
  ["fg-inverse", "fg"],
  ["primary", "surface"],
  ["primary-fg", "primary"],
  ["fg", "primary-soft"],
  ["success-fg", "success"],
  ["warning-fg", "warning"],
  ["danger-fg", "danger"],
  ["info-fg", "info"],
  ["fg", "success-soft"],
  ["fg", "warning-soft"],
  ["fg", "danger-soft"],
  ["fg", "info-soft"],
  ["code-fg", "code-bg"],
  ["syntax-comment", "code-bg"],
  ["syntax-keyword", "code-bg"],
  ["syntax-string", "code-bg"],
  ["syntax-number", "code-bg"],
  ["syntax-function", "code-bg"],
  ["syntax-type", "code-bg"],
  ["syntax-variable", "code-bg"],
]);
