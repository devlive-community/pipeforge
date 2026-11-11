/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { DICTIONARIES, detectLanguage, translate, type MessageKey } from "@/i18n";

function keys(value: unknown, prefix = ""): string[] {
  return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) =>
    typeof child === "string" ? [prefix + key] : keys(child, `${prefix}${key}.`),
  );
}

describe("i18n", () => {
  it("has the same non-empty messages in both languages", () => {
    const english = keys(DICTIONARIES["en-US"]).sort();
    expect(keys(DICTIONARIES["zh-CN"]).sort()).toEqual(english);
    for (const key of english) {
      expect(translate("zh-CN", key as MessageKey).trim(), key).not.toBe("");
      expect(translate("en-US", key as MessageKey).trim(), key).not.toBe("");
    }
  });

  it("fills placeholders and falls back to the key", () => {
    expect(translate("en-US", "overview.pluginsUnavailable", { count: 2 })).toBe("2 could not be loaded");
    expect(translate("zh-CN", "overview.pluginsUnavailable", { count: 2 })).toBe("2 个无法加载");
    expect(translate("en-US", "overview.pluginsUnavailable")).toBe("{count} could not be loaded");
    expect(translate("en-US", "nope.missing" as MessageKey)).toBe("nope.missing");
  });

  it("detects Chinese browsers", () => {
    expect(detectLanguage("zh-TW")).toBe("zh-CN");
    expect(detectLanguage("en-GB")).toBe("en-US");
    expect(detectLanguage(undefined)).toBe("en-US");
  });
});
