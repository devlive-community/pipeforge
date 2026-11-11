/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

/** API answers for the plugin, function and error code pages. */
export const PLUGINS = {
  plugins: [
    {
      id: "fake",
      name: "Fake",
      version: "0.1.0",
      apiVersion: "0.x",
      description: "Generates rows for tests and demos.",
      vendor: "PipeForge",
      license: "MIT",
      components: [
        { type: "source", identifier: "fake" },
        { type: "sink", identifier: "blackhole" },
      ],
      drivers: [],
    },
  ],
  unavailable: [{ location: "plugins/broken-1.0.0", reason: "provides must list at least one component" }],
};

export const FAKE_SOURCE = {
  type: "source",
  identifier: "fake",
  plugin: "fake@0.1.0",
  options: [
    { key: "rows", type: "LONG", requirement: "optional", defaultValue: 1000, min: 0, description: "Rows to produce" },
    { key: "table", type: "STRING", requirement: "required", description: "The table to produce" },
  ],
  exclusive: [["rows", "rate"]],
  conditions: [{ option: "mode", value: "random", required: ["seed"] }],
  nativeNamespaces: [],
};

export const FUNCTIONS = [
  {
    name: "UPPER",
    category: "string",
    description: "The text in upper case.",
    signatures: ["UPPER(STRING) -> STRING"],
    deterministic: true,
    examples: [{ expression: "UPPER('pipe')", result: "'PIPE'" }],
  },
  {
    name: "ABS",
    category: "numeric",
    description: "The absolute value.",
    signatures: ["ABS(INT64) -> INT64"],
    deterministic: true,
    examples: [{ expression: "ABS(-3)", result: "3" }],
  },
  {
    name: "NOW",
    category: "datetime",
    description: "The current time.",
    signatures: ["NOW() -> TIMESTAMP_LTZ(3)"],
    deterministic: false,
    nulls: "Never NULL.",
    examples: [],
  },
];

export const ERRORS = [
  {
    code: "PF-CONF-0001",
    name: "OPTION_MISSING",
    category: "CONF",
    kind: "FATAL",
    message: "The required option '{option}' is not set",
    hint: "Set the option in the node or in env.",
    params: ["option"],
    deprecated: false,
  },
  {
    code: "PF-PLAN-0007",
    name: "EXPRESSION_INVALID",
    category: "PLAN",
    kind: "FATAL",
    message: "The expression is invalid at line {line}, column {column}: {reason}",
    params: ["line", "column", "reason"],
    deprecated: false,
  },
  {
    code: "PF-SYS-0001",
    name: "UNAVAILABLE",
    category: "SYS",
    kind: "TRANSIENT",
    message: "The service is unavailable",
    params: [],
    deprecated: true,
  },
];
