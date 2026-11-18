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
  await page.getByRole("radio", { name: "YAML", exact: true }).click();
  await page.getByRole("textbox", { name: "Job definition (YAML)" }).click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.insertText(text);
}

test("validates while typing, marks the problem and shows the plan once valid", async ({ page }) => {
  await page.goto("/editor");
  await expect(page.getByText("Valid", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "print, sink console" })).toBeVisible();
  await expect(page.getByRole("tabpanel")).toBeHidden();
  await page.getByRole("tab", { name: "Plan" }).click();
  await page.getByRole("button", { name: "Hide results" }).click();

  await replaceDefinition(page, EXAMPLE_JOB.replace("type: console", "type: kafka"));
  await expect(page.getByText("1 problem(s)")).toBeVisible();
  await expect(page.getByText("The sink plugin 'kafka' is not installed")).toBeVisible();
  await expect(page.locator(".cm-lint-marker-error")).toHaveCount(1);
  await page.getByRole("radio", { name: "Topology" }).click();
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

test("switches views on a narrow screen and jumps from topology to YAML", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/editor");
  await expect(page.getByText("Valid", { exact: true })).toBeVisible();
  const yaml = page.getByRole("textbox", { name: "Job definition (YAML)" });
  await expect(yaml).toBeHidden();
  const library = page.getByRole("complementary", { name: "Component library" });
  const properties = page.getByRole("complementary", { name: "Node configuration" });
  await expect(library).toBeHidden();
  await expect(properties).toBeHidden();
  await page.getByRole("button", { name: "Component library" }).click();
  await expect(library).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(library).toBeHidden();
  const node = page.getByRole("button", { name: "print, sink console" });
  await node.click();
  await expect(node).toHaveAttribute("aria-pressed", "true");
  await expect(properties).toBeVisible();
  await page.getByRole("button", { name: "Go to YAML" }).click();
  await expect(yaml).toBeFocused();
  await expect(properties).toBeHidden();
  await expect(page.locator(".cm-activeLine")).toHaveText("  - id: print");
  await expect(page.getByRole("button", { name: "Show results" })).toHaveAttribute("aria-expanded", "false");
  await page.getByRole("button", { name: "Show results" }).click();
  await expect(page.getByRole("tabpanel")).toContainText("No problems");
  await page.getByRole("button", { name: "Hide results" }).click();
  await expect(page.getByRole("tabpanel")).toBeHidden();

  for (const viewport of [
    { width: 390, height: 844 },
    { width: 1040, height: 640 },
  ]) {
    await page.setViewportSize(viewport);
    await page.getByRole("tab", { name: "Plan" }).click();
    const results = page.getByRole("tabpanel");
    await expect(results.getByRole("table", { name: "Nodes" })).toBeVisible();
    const scrolledInside = await results.evaluate((pane) => {
      pane.scrollTop = pane.scrollHeight;
      return pane.scrollTop;
    });
    expect(scrolledInside).toBeGreaterThan(0);
    const sizes = await page.locator("main").evaluate((main) => {
      const dock = main.querySelector(".editor-results")!.getBoundingClientRect();
      return {
        viewport: window.innerHeight,
        documentHeight: document.documentElement.scrollHeight,
        mainHeight: main.clientHeight,
        mainContentHeight: main.scrollHeight,
        dockBottom: Math.round(dock.bottom),
      };
    });
    expect(sizes).toEqual({
      viewport: viewport.height,
      documentHeight: viewport.height,
      mainHeight: viewport.height,
      mainContentHeight: viewport.height,
      dockBottom: viewport.height,
    });
  }
});

test("fills the viewport and resizes all three editor panels", async ({ page }) => {
  await page.setViewportSize({ width: 1360, height: 820 });
  await page.goto("/editor");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("orders-daily");
  const left = page.getByRole("separator", { name: "Resize component library" });
  const right = page.getByRole("separator", { name: "Resize node properties" });
  const bottom = page.getByRole("separator", { name: "Resize results" });
  async function drag(handle: typeof left, dx: number, dy: number) {
    const box = (await handle.boundingBox())!;
    const x = box.x + box.width / 2;
    const y = box.y + box.height / 2;
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + dx, y + dy, { steps: 5 });
    await page.mouse.up();
  }
  const library = page.getByRole("complementary", { name: "Component library" });
  const properties = page.getByRole("complementary", { name: "Node configuration" });
  const libraryWidth = (await library.boundingBox())!.width;
  const propertiesWidth = (await properties.boundingBox())!.width;
  await drag(left, 60, 0);
  await drag(right, -55, 0);
  expect((await library.boundingBox())!.width).toBeGreaterThan(libraryWidth + 45);
  expect((await properties.boundingBox())!.width).toBeGreaterThan(propertiesWidth + 40);
  await drag(bottom, 0, -160);
  await expect(page.getByRole("tabpanel")).toBeVisible();
  await expect(bottom).not.toHaveAttribute("aria-valuenow", "14");
  expect(await page.evaluate(() => document.documentElement.scrollHeight)).toBe(820);
  await right.press("ArrowRight");
  await expect(right).toBeFocused();
  expect((await properties.boundingBox())!.width).toBeLessThan(propertiesWidth + 50);
});

test("adds, moves and connects a component in the topology", async ({ page }) => {
  await page.goto("/editor");
  const graph = page.getByRole("group", { name: "Graph of the job" });
  await page.getByRole("button", { name: "Add fake", exact: true }).dragTo(graph, {
    targetPosition: { x: 110, y: 80 },
  });
  const node = graph.getByRole("button", { name: "fake, source fake", exact: true });
  await expect(node).toBeVisible();
  const before = (await node.boundingBox())!;
  await page.mouse.move(before.x + before.width / 2, before.y + before.height / 2);
  await page.mouse.down();
  await page.mouse.move(before.x + before.width / 2 + 42, before.y + before.height / 2 + 35, { steps: 6 });
  await page.mouse.up();
  expect((await node.boundingBox())!.y).toBeGreaterThan(before.y + 25);

  const output = graph.getByRole("button", { name: "Output of fake", exact: true });
  await output.dragTo(graph.getByRole("button", { name: "Input of rename", exact: true }));
  await expect(page.getByRole("button", { name: "Remove input orders" })).toBeVisible();
  await page.getByRole("radio", { name: "YAML", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Job definition (YAML)" })).toContainText("inputs: [orders, fake]");
  await page.getByRole("button", { name: "Remove input orders" }).click();
  await expect(page.getByRole("textbox", { name: "Job definition (YAML)" })).toContainText("inputs: [fake]");
});
