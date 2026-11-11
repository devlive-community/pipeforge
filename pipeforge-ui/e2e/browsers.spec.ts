/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { expect, test } from "@playwright/test";
import { mockServer } from "./mocks";

test.beforeEach(async ({ page }) => {
  await mockServer(page);
});

test("plugins show the options of a component and keep it in the address", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Open Plugins" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Plugins" })).toBeVisible();
  await page.getByRole("button", { name: "Source fake" }).click();
  await expect(page).toHaveURL(/type=source&component=fake&plugin=fake%400.1.0/);
  await expect(page.getByText("Rows to generate; the source is bounded.")).toBeVisible();
  await expect(page.getByText("Set exactly one of: rows, rate")).toBeVisible();
  await page.reload();
  await expect(page.getByRole("button", { name: "Source fake" })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("Rows to generate; the source is bounded.")).toBeVisible();
});

test("functions filter, try an example and evaluate with completion", async ({ page }) => {
  await page.goto("/functions");
  await expect(page.getByText("88 functions")).toBeVisible();
  await page.getByRole("radio", { name: "String" }).click();
  await page.getByRole("textbox", { name: "Search functions" }).fill("upper");
  const upper = page.locator("article#upper");
  await expect(upper).toBeVisible();
  await upper.getByRole("button", { name: "Try" }).first().click();
  const editor = page.getByTestId("code-editor").locator(".cm-content");
  await expect(editor).toContainText("UPPER(");

  await page.getByRole("button", { name: "Evaluate" }).click();
  await expect(page.getByRole("region", { name: "Result" })).toContainText("PIPEFORGE pays 25.00");

  await editor.click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type("UPPR");
  await page.getByRole("button", { name: "Evaluate" }).click();
  const result = page.getByRole("region", { name: "Result" });
  await expect(result).toContainText("PF-PLAN-0007");
  await expect(page.locator(".cm-lintRange-error")).toBeVisible();

  await editor.click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type("COALES");
  await expect(page.getByRole("option", { name: /COALESCE/ })).toBeVisible();
  await page.waitForTimeout(150);
  await page.keyboard.press("Enter");
  await expect(editor).toContainText("COALESCE(");
});

test("an error code opens from its address in Chinese", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("pipeforge-language", "zh-CN"));
  await page.goto("/errors#pf-plan-0007");
  const entry = page.locator("article#pf-plan-0007");
  await expect(entry).toBeFocused();
  await expect(entry).toContainText("表达式");
  await expect(entry).toBeInViewport();
  await page.getByRole("radio", { name: "SYS" }).click();
  await expect(page.locator("article#pf-plan-0007")).toHaveCount(0);
});
