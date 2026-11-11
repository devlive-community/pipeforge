/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { systemTheme } from "./setup";

async function load() {
  vi.resetModules();
  return import("@/state/preferences");
}

describe("preferences", () => {
  it("applies and remembers the theme", async () => {
    const { usePreferences } = await load();
    expect(usePreferences.getState().theme).toBe("system");
    usePreferences.getState().setTheme("dark");
    expect(document.documentElement).toHaveClass("dark");
    expect(document.documentElement.dataset.themeMode).toBe("dark");
    expect(localStorage.getItem("pipeforge-theme")).toBe("dark");
    usePreferences.getState().setTheme("light");
    expect(document.documentElement).not.toHaveClass("dark");
  });

  it("follows the system in system mode", async () => {
    const { applyTheme } = await load();
    systemTheme.dark = true;
    applyTheme("system");
    expect(document.documentElement).toHaveClass("dark");
    systemTheme.dark = false;
    applyTheme("system");
    expect(document.documentElement).not.toHaveClass("dark");
  });

  it("restores stored choices and detects the language otherwise", async () => {
    localStorage.setItem("pipeforge-theme", "dark");
    localStorage.setItem("pipeforge-language", "zh-CN");
    let { usePreferences } = await load();
    expect(usePreferences.getState()).toMatchObject({ theme: "dark", language: "zh-CN" });

    localStorage.clear();
    localStorage.setItem("pipeforge-language", "fr-FR");
    ({ usePreferences } = await load());
    expect(usePreferences.getState().language).toBe("en-US");
    usePreferences.getState().setLanguage("zh-CN");
    expect(localStorage.getItem("pipeforge-language")).toBe("zh-CN");
    expect(document.documentElement.lang).toBe("zh-CN");
  });

  it("keeps working when storage is refused", async () => {
    const getItem = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("denied");
    });
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("denied");
    });
    try {
      const { usePreferences } = await load();
      expect(usePreferences.getState().theme).toBe("system");
      usePreferences.getState().setTheme("dark");
      expect(usePreferences.getState().theme).toBe("dark");
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
  });
});
