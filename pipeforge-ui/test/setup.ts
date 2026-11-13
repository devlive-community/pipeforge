/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";

// Pages with the code editor are loaded lazily, and a busy machine (a full build next to the tests)
// can take well over the default second to render them; findBy and waitFor wait up to 5 seconds.
configure({ asyncUtilTimeout: 5000 });

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

// jsdom has no PointerEvent; pointer handlers read clientX and pointerId from it.
if (typeof window.PointerEvent === "undefined") {
  class PointerEvent extends MouseEvent {
    readonly pointerId: number;
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 0;
    }
  }
  Object.defineProperty(window, "PointerEvent", { writable: true, value: PointerEvent });
}

afterEach(() => {
  cleanup();
  localStorage.clear();
  systemTheme.dark = false;
  systemTheme.listeners.clear();
  document.documentElement.className = "";
  vi.unstubAllGlobals();
});
