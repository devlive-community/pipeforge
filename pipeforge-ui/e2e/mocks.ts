/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Page } from "@playwright/test";
import { deriveRows, findColumn } from "../src/features/field-mapping/model";
import { readMapper } from "../src/features/field-mapping/yamlEdit";
import { JOB_SCHEMA } from "../test/features/job-editor/schema-fixture";
// The function and error code catalogs as the server answers them, in both languages.
import ERRORS_EN from "./fixtures/errors.en-US.json" with { type: "json" };
import ERRORS_ZH from "./fixtures/errors.zh-CN.json" with { type: "json" };
import FUNCTIONS_EN from "./fixtures/functions.en-US.json" with { type: "json" };
import FUNCTIONS_ZH from "./fixtures/functions.zh-CN.json" with { type: "json" };

const chinese = (language: string) => language.startsWith("zh");

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
              { name: "amount", type: "DECIMAL(10, 2)", nullable: true },
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

/**
 * The plan of a job: the example plan, with the output of the field mapper rename derived from
 * its options in the definition the way the plugin derives it (names, order, types of entries).
 */
function explain(body: string) {
  const definition = (JSON.parse(body) as { definition: string }).definition;
  const plan = structuredClone(PLAN);
  const [orders, rename, print] = plan.plan.nodes;
  const input = orders?.outputTables[0];
  const options = readMapper(definition, "rename");
  if (!input || !rename || !print || !options) return plan;
  const columns = input.columns.map((c) => ({ name: c.name, type: c.type, nullable: c.nullable }));
  const output = {
    ...input,
    primaryKey: [],
    columns: deriveRows(options, columns).map((row) => ({
      name: row.name,
      type:
        row.type ??
        (row.source === "column" && row.input ? (findColumn(columns, row.input)?.type ?? "STRING") : "STRING"),
      nullable: true,
    })),
  };
  (rename as { inputTables: unknown[] }).inputTables = [input];
  (rename as { outputTables: unknown[] }).outputTables = [output];
  (print as { inputTables: unknown[] }).inputTables = [output];
  return plan;
}

/** Validation like the server's: a kafka sink fails at the node print, in the requested language. */
function validate(body: string, language: string) {
  return body.includes("kafka")
    ? {
        valid: false,
        errors: [
          {
            code: "PF-PLUG-0001",
            message: chinese(language) ? "sink 插件 'kafka' 未安装" : "The sink plugin 'kafka' is not installed",
            hint: chinese(language)
              ? "把插件安装到 plugins 目录，或者检查插件名称的拼写。"
              : "Install the plugin into the plugins directory, or check the spelling of its name.",
            scope: { node: "print" },
          },
        ],
        warnings: [],
      }
    : { valid: true, errors: [], warnings: [] };
}

const FAKE_SOURCE = {
  type: "source",
  identifier: "fake",
  plugin: "fake@0.1.0",
  options: [
    {
      key: "table",
      type: "string",
      requirement: "required",
      description: "Name of the table to generate, e.g. demo.orders.",
    },
    {
      key: "schema",
      type: "list",
      requirement: "required",
      description: "Columns of the table: name, type and generator.",
    },
    {
      key: "rows",
      type: "long",
      requirement: "one-of",
      defaultValue: 1000,
      min: 0,
      description: "Rows to generate; the source is bounded.",
    },
    {
      key: "rate",
      type: "integer",
      requirement: "one-of",
      min: 1,
      description: "Rows per second; the source never ends.",
    },
    {
      key: "disorder",
      type: "enum",
      requirement: "optional",
      defaultValue: "none",
      values: ["none", "shuffle", "late"],
      description: "Emit rows out of order to test event-time handling.",
    },
    {
      key: "seed",
      type: "long",
      requirement: "conditional",
      description: "Seed of the random generator.",
      since: "0.1.0",
    },
  ],
  exclusive: [["rows", "rate"]],
  conditions: [{ option: "disorder", value: "shuffle", required: ["seed"] }],
  nativeNamespaces: [],
};

const CONSOLE_SINK = {
  type: "sink",
  identifier: "console",
  plugin: "console@0.1.0",
  options: [
    {
      key: "format",
      type: "enum",
      requirement: "optional",
      defaultValue: "table",
      values: ["table", "json"],
      description: "How rows are printed.",
    },
    {
      key: "limit",
      type: "long",
      requirement: "optional",
      defaultValue: 100,
      min: 0,
      description: "Rows to print; 0 prints all.",
    },
  ],
  exclusive: [],
  conditions: [],
  nativeNamespaces: [],
};

/** Evaluates like the server: the example expression, NULL for a missing value, an error for a typo. */
function evaluate(body: string, language: string) {
  const request = JSON.parse(body) as { expression: string };
  if (request.expression.includes("UPPR")) {
    return {
      status: 400,
      code: "PF-PLAN-0007",
      params: { line: 1, column: 1, reason: "unknown function UPPR" },
      message: chinese(language)
        ? "表达式 'UPPR(name)' 在第 1 行第 1 列有误：unknown function UPPR"
        : "The expression 'UPPR(name)' is invalid at line 1, column 1: unknown function UPPR",
      hint: chinese(language)
        ? "检查语法、列名以及函数的参数类型。"
        : "Check the syntax, the column names and the argument types of the functions.",
    };
  }
  return { type: "STRING", value: "PIPEFORGE pays 25.00", deterministic: true };
}

/** API answers of a server with two plugins, one rejected package, functions, error codes and jobs. */
export const SERVER: Record<string, unknown> = {
  "/api/v1/version": { version: "0.1.0", connectorApi: "0.1.0", java: "21.0.10+8-LTS" },
  "/api/v1/plugins": {
    plugins: [
      {
        id: "fake",
        name: "Fake",
        version: "0.1.0",
        apiVersion: "0.x",
        description: "Generates rows from a schema, with a controlled rate, disorder and bad records.",
        vendor: "PipeForge",
        license: "MIT",
        components: [{ type: "source", identifier: "fake" }],
        drivers: [],
      },
      {
        id: "console",
        name: "Console",
        version: "0.1.0",
        apiVersion: "0.x",
        description: "Prints rows as a table or as JSON.",
        vendor: "PipeForge",
        license: "MIT",
        components: [{ type: "sink", identifier: "console" }],
        drivers: [],
      },
    ],
    unavailable: [
      { location: "plugins/broken-1.0.0", reason: "Invalid plugin.yaml: provides must list at least one component" },
    ],
  },
  "/api/v1/components/source/fake": FAKE_SOURCE,
  "/api/v1/components/sink/console": CONSOLE_SINK,
  "/api/v1/functions": (_: string, language: string) => (chinese(language) ? FUNCTIONS_ZH : FUNCTIONS_EN),
  "/api/v1/errors": (_: string, language: string) => (chinese(language) ? ERRORS_ZH : ERRORS_EN),
  "/api/v1/expressions/evaluate": evaluate,
  "/api/v1/jobs/schema": JOB_SCHEMA,
  "/api/v1/jobs/validate": validate,
  "/api/v1/explain": explain,
};

/**
 * Answers /api requests from `answers`: a body, a status number (an error object with that status),
 * or a function of the request body and its Accept-Language returning the answer. An answer with a
 * `status` is sent with that status, without the field.
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
    } else if (typeof answer === "object" && answer !== null && "status" in answer) {
      const { status, ...json } = answer as { status: number };
      await route.fulfill({ status, json });
    } else {
      await route.fulfill({ json: answer });
    }
  });
}
