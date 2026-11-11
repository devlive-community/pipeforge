/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Page } from "@playwright/test";

/** API answers of a server with two plugins, one rejected package, functions and error codes. */
export const SERVER: Record<string, unknown> = {
  "/api/v1/version": { version: "0.1.0", connectorApi: "0.1.0", java: "21.0.10+8-LTS" },
  "/api/v1/plugins": {
    plugins: [
      { id: "fake", name: "Fake", version: "0.1.0", apiVersion: "0.x", components: [], drivers: [] },
      { id: "console", name: "Console", version: "0.1.0", apiVersion: "0.x", components: [], drivers: [] },
    ],
    unavailable: [
      { location: "plugins/broken-1.0.0", reason: "Invalid plugin.yaml: provides must list at least one component" },
    ],
  },
  "/api/v1/functions": Array.from({ length: 88 }, (_, i) => ({ name: `F${i}` })),
  "/api/v1/errors": Array.from({ length: 28 }, (_, i) => ({ code: `PF-CONF-${i}` })),
};

/** Answers /api requests from `answers`; a path mapped to a number answers with that status. */
export async function mockServer(page: Page, answers: Record<string, unknown> = SERVER) {
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const answer = answers[path];
    if (typeof answer === "number") {
      await route.fulfill({ status: answer, json: { code: "PF-SYS-0001", message: "down" } });
    } else if (answer === undefined) {
      await route.fulfill({ status: 404, json: { code: "PF-CONF-0007", message: "no endpoint" } });
    } else {
      await route.fulfill({ json: answer });
    }
  });
}
