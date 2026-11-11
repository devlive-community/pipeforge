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
import { EXAMPLE_JOB } from "../src/features/job-editor/example";
import { mockServer } from "./mocks";

const OUT = fileURLToPath(new URL("../../docs/public/screenshots/ui/", import.meta.url));

interface Shot {
  name: string;
  path: string;
  /** Text that is visible once the page is ready, per language. */
  ready: Record<"en-US" | "zh-CN", string>;
  /** A job draft for the editor. */
  draft?: string;
  /** A tab to open before the screenshot, per language. */
  tab?: Record<"en-US" | "zh-CN", string>;
  /** A button to press before the screenshot, per language. */
  click?: Record<"en-US" | "zh-CN", string>;
}

const PAGES: Shot[] = [
  { name: "overview", path: "/", ready: { "en-US": "Connected", "zh-CN": "已连接" } },
  {
    name: "job-editor",
    path: "/editor",
    ready: { "en-US": "Valid", "zh-CN": "合法" },
    draft: EXAMPLE_JOB,
    tab: { "en-US": "Plan", "zh-CN": "计划" },
  },
  {
    name: "plugins",
    path: "/plugins?type=source&component=fake&plugin=fake%400.1.0",
    ready: { "en-US": "Rows to generate; the source is bounded.", "zh-CN": "Rows to generate; the source is bounded." },
  },
  {
    name: "functions",
    path: "/functions",
    ready: { "en-US": "88 functions", "zh-CN": "88 个函数" },
    click: { "en-US": "Evaluate", "zh-CN": "求值" },
  },
  { name: "error-codes", path: "/errors", ready: { "en-US": "28 codes", "zh-CN": "28 个错误码" } },
  {
    name: "job-editor-problem",
    path: "/editor",
    ready: { "en-US": "1 problem(s)", "zh-CN": "1 个问题" },
    draft: EXAMPLE_JOB.replace("type: console", "type: kafka"),
  },
];

for (const shot of PAGES) {
  for (const theme of ["light", "dark"] as const) {
    for (const language of ["en-US", "zh-CN"] as const) {
      test(`${shot.name} ${theme} ${language}`, async ({ page }) => {
        await mockServer(page);
        await page.addInitScript(
          (preferences) => {
            localStorage.setItem("pipeforge-theme", preferences.theme);
            localStorage.setItem("pipeforge-language", preferences.language);
            if (preferences.draft !== undefined) localStorage.setItem("pipeforge-job-draft", preferences.draft);
          },
          { theme, language, draft: shot.draft },
        );
        await page.goto(shot.path);
        await expect(page.getByText(shot.ready[language], { exact: true })).toBeVisible();
        if (shot.tab) await page.getByRole("tab", { name: shot.tab[language] }).click();
        if (shot.click) {
          await page.getByRole("button", { name: shot.click[language], exact: true }).click();
          await expect(page.getByText("PIPEFORGE pays 25.00")).toBeVisible();
        }
        await page.mouse.move(0, 0);
        await page.screenshot({ path: `${OUT}${shot.name}-${theme}-${language}.png`, animations: "disabled" });
      });
    }
  }
}
