/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { expect, test } from "@playwright/test";
import { mockServer } from "./mocks";

let problems: string[] = [];

test.beforeEach(async ({ page }) => {
  problems = [];
  page.on("pageerror", (error) => problems.push(error.message));
  page.on("console", (message) => {
    // Failed requests are expected where a test mocks an error; the assertions cover them.
    if (message.type() === "error" && !message.text().startsWith("Failed to load resource")) {
      problems.push(message.text());
    }
  });
  await mockServer(page);
});

test.afterEach(() => {
  expect(problems, "uncaught errors or console errors of the page").toEqual([]);
});

test("shows the overview with the server and what is installed", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Connected", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { level: 1, name: "Overview" })).toBeVisible();
  await expect(page.getByText("88", { exact: true })).toBeVisible();
  await expect(page.getByText("1 could not be loaded")).toBeVisible();
  await expect(page.getByText("plugins/broken-1.0.0")).toBeVisible();
});

test("applies the system theme before the first frame", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await page.goto("/", { waitUntil: "commit" });
  await page.waitForSelector("html.dark", { state: "attached" });
  expect(await page.evaluate(() => document.documentElement.dataset.themeMode)).toBe("system");
});

test("remembers the theme and the language", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Theme" }).click();
  await page.getByRole("menuitemradio", { name: "Dark" }).click();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await page.getByRole("button", { name: "Language" }).click();
  await page.getByRole("menuitemradio", { name: "中文" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "概览" })).toBeVisible();
  await page.reload();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await expect(page.locator("html")).toHaveAttribute("lang", "zh-CN");
  await expect(page.getByText("已连接")).toBeVisible();
});

test("opens deep links and explains unknown pages", async ({ page }) => {
  await page.goto("/no/such/page");
  await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
  await page.getByRole("link", { name: "Back to the overview" }).click();
  await expect(page).toHaveURL(/\/$/);
});

test("explains when the server is down", async ({ page }) => {
  await page.unroute("**/api/**");
  await mockServer(page, { "/api/v1/version": 503 });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "The server cannot be reached" })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("button", { name: "Retry" })).toBeEnabled();
});

test("is usable with the keyboard alone", async ({ page }) => {
  await page.goto("/");
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Skip to content" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "Language" })).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(page.getByRole("menuitemradio", { name: "English" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("button", { name: "Language" })).toBeFocused();
});
