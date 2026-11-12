/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { expect, test, type Locator, type Page } from "@playwright/test";
import { MAPPED_JOB } from "./jobs";
import { mockServer } from "./mocks";

let problems: string[] = [];

test.beforeEach(async ({ page }) => {
  problems = [];
  page.on("pageerror", (error) => problems.push(error.message));
  await mockServer(page);
  await page.addInitScript((draft) => localStorage.setItem("pipeforge-job-draft", draft), MAPPED_JOB);
});

test.afterEach(() => {
  expect(problems, "uncaught errors of the page").toEqual([]);
});

/** Drags with the mouse, in small steps, from the middle of one element to the middle of another. */
async function drag(page: Page, from: Locator, to: Locator) {
  await to.scrollIntoViewIfNeeded();
  await from.scrollIntoViewIfNeeded();
  const a = await from.boundingBox();
  const b = await to.boundingBox();
  if (!a || !b) throw new Error("not visible");
  await page.mouse.move(a.x + a.width / 3, a.y + a.height / 2);
  await page.mouse.down();
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 8 });
  await page.mouse.up();
}

const editorText = (page: Page) => page.getByTestId("code-editor").locator(".cm-content");

test("drags an input column onto an output column and the plan follows", async ({ page }) => {
  await page.goto("/editor");
  await expect(page.getByText("Valid", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "rename, transform field-mapper" }).click();
  const outputs = page.getByRole("region", { name: "Output columns" });
  await expect(outputs.getByRole("button", { name: "loud, STRING" })).toBeVisible();

  await drag(page, page.locator('[data-input="amount"]'), outputs.getByRole("button", { name: "loud, STRING" }));
  await expect(editorText(page)).toContainText("- { from: amount, to: loud }");
  await expect(editorText(page)).toContainText("- id: rename           # tidy the columns");
  // revalidated and explained again: the output column now has the type of amount
  await expect(outputs.getByRole("button", { name: "loud, DECIMAL(10, 2)" })).toBeVisible();

  await drag(page, page.locator('[data-input="name"]'), page.getByText("New column", { exact: true }));
  await expect(editorText(page)).toContainText("- { from: name }");
  await expect(page.getByRole("region", { name: "Column name" })).toBeVisible();

  const name = page.getByRole("region", { name: "Column name" }).getByRole("textbox", { name: "Name" });
  await name.fill("customer");
  await name.press("Enter");
  await expect(editorText(page)).toContainText("- { from: name, to: customer }");
  await expect(outputs.getByRole("button", { name: "customer, STRING(20)" })).toBeVisible();

  await page.getByRole("tab", { name: "Plan" }).click();
  await expect(page.getByRole("table", { name: "rename produces demo.orders" })).toContainText("customer");
});

test("drops a column, changes the naming and keeps the rest of the definition", async ({ page }) => {
  await page.goto("/editor");
  await expect(page.getByText("Valid", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "Mapping" }).click();
  await page.getByRole("button", { name: "Drop id" }).click();
  await page.getByRole("radio", { name: "UPPER" }).click();
  await expect(editorText(page)).toContainText("drop: [id]");
  await expect(editorText(page)).toContainText("name-case: upper");
  const outputs = page.getByRole("region", { name: "Output columns" });
  await expect(outputs.getByRole("button", { name: /^NAME, / })).toBeVisible();
  await expect(outputs.getByRole("button", { name: /^id, / })).toHaveCount(0);
  await expect(editorText(page)).toContainText('biz_date: "${date:yyyy-MM-dd,-1d}"');
});
