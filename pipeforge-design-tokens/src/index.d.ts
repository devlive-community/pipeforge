/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

/** Key in localStorage that stores the chosen theme mode. */
export declare const THEME_STORAGE_KEY: "pipeforge-theme";

/** Theme modes offered by the UI and the documentation site. */
export declare const THEME_MODES: readonly ["light", "dark", "system"];

/** Inline script for <head> that applies the theme before the first frame. */
export declare const themeInitScript: string;

/** Text and background token pairs that must reach a contrast of 4.5:1 in both themes. */
export declare const TEXT_CONTRAST_PAIRS: readonly (readonly [string, string])[];
