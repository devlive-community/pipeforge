/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Page } from "@playwright/test";
import { JOB_SCHEMA } from "../test/features/job-editor/schema-fixture";

/** The plan of the example job, as POST /api/v1/explain answers it. */
export const PLAN = {
  plan: {
    job: {
      name: "orders-daily",
      engine: "native",
      mode: "batch",
      modeDerived: true,
      delivery: "at-least-once",
      parallelism: 2,
      timezone: "UTC",
    },
    variables: { biz_date: "2026-11-10" },
    nodes: [
      {
        id: "orders",
        kind: "source",
        type: "fake",
        plugin: "fake@0.1.0",
        inputs: [],
        inputsImplied: false,
        parallelism: 2,
        boundedness: "bounded",
        options: [{ key: "rows", value: 100, origin: "node", sensitive: false }],
        inputTables: [],
        outputTables: [
          {
            id: "demo.orders",
            columns: [
              { name: "id", type: "INT64", nullable: false },
              { name: "name", type: "STRING(20)", nullable: true },
            ],
            primaryKey: ["id"],
            partitionKeys: [],
          },
        ],
      },
      {
        id: "rename",
        kind: "transform",
        type: "field-mapper",
        plugin: "field-mapper@0.1.0",
        inputs: ["orders"],
        inputsImplied: true,
        parallelism: 2,
        options: [],
        inputTables: [],
        outputTables: [],
      },
      {
        id: "print",
        kind: "sink",
        type: "console",
        plugin: "console@0.1.0",
        inputs: ["rename"],
        inputsImplied: true,
        parallelism: 2,
        options: [{ key: "limit", value: 50, origin: "cluster", sensitive: false }],
        inputTables: [],
        outputTables: [],
      },
    ],
    warnings: [],
  },
  text: "Job orders-daily  mode=batch (derived)  delivery=at-least-once  engine=native  parallelism=2  timezone=UTC\n",
};

/** Validation like the server's: a kafka sink fails at the node print, in the requested language. */
function validate(body: string, language: string) {
  const chinese = language.startsWith("zh");
  return body.includes("kafka")
    ? {
        valid: false,
        errors: [
          {
            code: "PF-PLUG-0001",
            message: chinese ? "sink 插件 'kafka' 未安装" : "The sink plugin 'kafka' is not installed",
            hint: chinese
              ? "把插件安装到 plugins 目录，或者检查插件名称的拼写。"
              : "Install the plugin into the plugins directory, or check the spelling of its name.",
            scope: { node: "print" },
          },
        ],
        warnings: [],
      }
    : { valid: true, errors: [], warnings: [] };
}

/** API answers of a server with two plugins, one rejected package, functions, error codes and jobs. */
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
  "/api/v1/jobs/schema": JOB_SCHEMA,
  "/api/v1/jobs/validate": validate,
  "/api/v1/explain": PLAN,
};

/**
 * Answers /api requests from `answers`: a body, a status number (an error object with that status),
 * or a function of the request body and its Accept-Language returning the answer.
 */
export async function mockServer(page: Page, answers: Record<string, unknown> = SERVER) {
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const handler = answers[new URL(request.url()).pathname];
    const language = (await request.allHeaders())["accept-language"] ?? "en-US";
    const answer =
      typeof handler === "function"
        ? (handler as (body: string, language: string) => unknown)(request.postData() ?? "", language)
        : handler;
    if (typeof answer === "number") {
      await route.fulfill({ status: answer, json: { code: "PF-SYS-0001", message: "down" } });
    } else if (answer === undefined) {
      await route.fulfill({ status: 404, json: { code: "PF-CONF-0007", message: "no endpoint" } });
    } else {
      await route.fulfill({ json: answer });
    }
  });
}
