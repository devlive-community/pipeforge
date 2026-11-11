/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";

/** jsdom has no matchMedia; tests switch the system theme through this flag. */
export const systemTheme = { dark: false, listeners: new Set<() => void>() };

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: query.includes("dark") ? systemTheme.dark : false,
    media: query,
    addEventListener: (_: string, listener: () => void) => systemTheme.listeners.add(listener),
    removeEventListener: (_: string, listener: () => void) => systemTheme.listeners.delete(listener),
  }),
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  systemTheme.dark = false;
  systemTheme.listeners.clear();
  document.documentElement.className = "";
  vi.unstubAllGlobals();
});
