/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { expect, test, type Page } from "@playwright/test";
import { EXAMPLE_JOB } from "../src/features/job-editor/example";
import { mockServer } from "./mocks";

let problems: string[] = [];

test.beforeEach(async ({ page }) => {
  problems = [];
  page.on("pageerror", (error) => problems.push(error.message));
  await mockServer(page);
});

test.afterEach(() => {
  expect(problems, "uncaught errors of the page").toEqual([]);
});

/** Replaces the whole definition as if it was typed. */
async function replaceDefinition(page: Page, text: string) {
  await page.getByRole("textbox", { name: "Job definition (YAML)" }).click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.insertText(text);
}

test("validates while typing, marks the problem and shows the plan once valid", async ({ page }) => {
  await page.goto("/editor");
  await expect(page.getByText("Valid", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "print, sink console" })).toBeVisible();

  await replaceDefinition(page, EXAMPLE_JOB.replace("type: console", "type: kafka"));
  await expect(page.getByText("1 problem(s)")).toBeVisible();
  await expect(page.getByText("The sink plugin 'kafka' is not installed")).toBeVisible();
  await expect(page.locator(".cm-lint-marker-error")).toHaveCount(1);
  await expect(page.getByRole("button", { name: "print, sink kafka, has a problem" })).toBeVisible();

  await page.getByRole("button", { name: "Line 15" }).click();
  await expect(page.locator(".cm-activeLine")).toHaveText("  - id: print");

  await replaceDefinition(page, EXAMPLE_JOB);
  await expect(page.getByText("Valid", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "Plan" }).click();
  await expect(page.getByRole("table", { name: "Nodes" })).toContainText("fake@0.1.0");
  await expect(page.getByRole("table", { name: "Options of node print" })).toContainText("cluster");
});

/** CodeMirror ignores Enter for a moment after the list opens (interactionDelay), against accidents. */
async function accept(page: Page) {
  await page.waitForTimeout(150);
  await page.keyboard.press("Enter");
}

test("completes keys and values from the job schema", async ({ page }) => {
  await page.goto("/editor");
  await replaceDefinition(page, "version: 1\nenv:\n  ");
  await page.keyboard.type("mo");
  const list = page.locator(".cm-tooltip-autocomplete");
  await expect(list).toContainText("mode");
  await accept(page);
  await expect(page.locator(".cm-content")).toContainText("mode:");
  await page.keyboard.type("st");
  await expect(list).toContainText("streaming");
  await accept(page);
  await expect(page.locator(".cm-content")).toContainText("mode: streaming");
});

test("keeps the draft across reloads and exports it", async ({ page }) => {
  await page.goto("/editor");
  await replaceDefinition(page, EXAMPLE_JOB.replace("orders-daily", "kept-draft"));
  await page.waitForTimeout(500);
  await page.reload();
  await expect(page.locator(".cm-content")).toContainText("name: kept-draft");
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export" }).click();
  expect((await download).suggestedFilename()).toBe("kept-draft.yaml");
});
