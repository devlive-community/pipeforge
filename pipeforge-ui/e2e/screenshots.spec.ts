/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Screenshots for the documentation: every page in light and dark, English and Chinese, written
// to docs/public/screenshots/ui/<page>-<theme>-<language>.png. Run with: pnpm screenshots
// UI changes regenerate them in the same commit.

import { fileURLToPath } from "node:url";
import { expect, test } from "@playwright/test";
import { mockServer } from "./mocks";

const OUT = fileURLToPath(new URL("../../docs/public/screenshots/ui/", import.meta.url));

const PAGES = [{ name: "overview", path: "/", ready: { "en-US": "Connected", "zh-CN": "已连接" } }];

for (const shot of PAGES) {
  for (const theme of ["light", "dark"] as const) {
    for (const language of ["en-US", "zh-CN"] as const) {
      test(`${shot.name} ${theme} ${language}`, async ({ page }) => {
        await mockServer(page);
        await page.addInitScript(
          (preferences) => {
            localStorage.setItem("pipeforge-theme", preferences.theme);
            localStorage.setItem("pipeforge-language", preferences.language);
          },
          { theme, language },
        );
        await page.goto(shot.path);
        await expect(page.getByText(shot.ready[language], { exact: true })).toBeVisible();
        await page.mouse.move(0, 0);
        await page.screenshot({ path: `${OUT}${shot.name}-${theme}-${language}.png`, animations: "disabled" });
      });
    }
  }
}
