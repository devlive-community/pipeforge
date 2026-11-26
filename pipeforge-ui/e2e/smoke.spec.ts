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

test("lists the runs and filters them by state", async ({ page }) => {
  await page.goto("/runs");
  await expect(page.getByRole("heading", { level: 1, name: "Runs" })).toBeVisible();
  await expect(page.getByText("users-sync")).toBeVisible();
  await expect(page.getByText("PF-DATA-0009")).toBeVisible();
  const request = page.waitForRequest((r) => r.url().includes("/api/v1/runs") && r.url().includes("status=FAILED"));
  await page.getByRole("radio", { name: "Failed" }).click();
  await request;
  await expect(page).toHaveURL(/state=failed/);
});

test("opens a run from the list and switches to its metrics", async ({ page }) => {
  await page.goto("/runs");
  await page.getByRole("link", { name: "orders-daily" }).first().click();
  await expect(page.getByRole("heading", { level: 1, name: "orders-daily" })).toBeVisible();
  await expect(page.getByText("The target is the slowest part")).toBeVisible();
  await page.getByRole("tab", { name: "Metrics" }).click();
  await expect(page).toHaveURL(/tab=metrics/);
  await expect(page.getByRole("img", { name: /Read and write rates/ })).toBeVisible();
  await page.getByRole("tab", { name: "Execution plan" }).click();
  await page.getByRole("button", { name: /^warehouse, sink postgresql, the bottleneck/ }).click();
  await expect(page).toHaveURL(/tab=plan&node=warehouse/);
  await expect(page.getByText("The run analysis finds this node to be the bottleneck; see below.")).toBeVisible();
});

test("reads a task's log and finds text in it", async ({ page }) => {
  await page.goto("/runs/3d0f9c1a-5b7e-4c2a-9f10-6a8e2b4c7d01?tab=logs");
  const log = page.getByRole("log", { name: "Log of the task" });
  await expect(log.getByText(/Communications link failure/)).toBeVisible();
  await page.getByRole("textbox", { name: "Find in the log" }).fill("reconnected");
  await expect(log.locator("mark")).toHaveText("reconnected");
  await expect(log.getByText(/Communications link failure/)).toHaveCount(0);
  await page.getByRole("tab", { name: "Splits" }).click();
  await expect(page.getByText("9 of 13 splits read")).toBeVisible();
});

test("lists the checkpoints of a run and takes a savepoint", async ({ page }) => {
  await page.route("**/api/v1/runs/*/savepoint", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ id: 8, type: "SAVEPOINT", status: "COMPLETED", path: "checkpoints/run/sp-8/", tasks: [] }),
    }),
  );
  await page.goto("/runs/3d0f9c1a-5b7e-4c2a-9f10-6a8e2b4c7d01?tab=checkpoints");
  const table = page.getByRole("table", { name: "Checkpoints and savepoints of the run, newest first" });
  await expect(table.getByText("before-index-rebuild")).toBeVisible();
  await expect(page.getByRole("table", { name: "Tasks of checkpoint #7" })).toBeVisible();
  await table.getByRole("button", { name: "#6" }).click();
  await expect(page).toHaveURL(/checkpoint=6/);
  await expect(page.getByRole("table", { name: "Tasks of checkpoint #6" })).toBeVisible();
  await page.getByLabel("Name").fill("before-upgrade");
  const request = page.waitForRequest((r) => r.url().endsWith("/savepoint"));
  await page.getByRole("button", { name: "Take savepoint" }).click();
  expect(JSON.parse((await request).postData() ?? "{}")).toEqual({ name: "before-upgrade" });
  await expect(page.getByText("Savepoint #8 completed at checkpoints/run/sp-8/")).toBeVisible();
});

test("shows the cluster with its workers", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Cluster" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Cluster" })).toBeVisible();
  await expect(page.getByText("14 of 24 in use")).toBeVisible();
  await expect(page.getByRole("img", { name: "Slots of worker-1: 7 of 8 in use" })).toBeVisible();
  await expect(page.getByText("Late")).toBeVisible();
});

test("dry-runs the job in the editor", async ({ page }) => {
  await page.goto("/editor");
  await expect(page.getByText("Valid", { exact: true })).toBeVisible();
  const request = page.waitForRequest((r) => r.url().endsWith("/api/v1/jobs/preview"));
  await page.getByRole("button", { name: "Dry run", exact: true }).click();
  expect(JSON.parse((await request).postData() ?? "{}")).not.toHaveProperty("node");
  await expect(page.getByRole("table", { name: "Rows of demo.orders" })).toBeVisible();
  await expect(page.getByText("Stopped at the row limit")).toBeVisible();
});

test("edits an option of a node into the definition", async ({ page }) => {
  await page.goto("/editor");
  await expect(page.getByText("Valid", { exact: true })).toBeVisible();
  const rows = page.getByRole("textbox", { name: "Value of rows" });
  await rows.fill("250");
  await rows.press("Enter");
  await expect(page.getByTestId("code-editor")).toContainText("rows: 250");
});
