/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

/** A reduced job schema as the server serves it, with one source, one transform and one sink. */
export const JOB_SCHEMA = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  properties: {
    version: { const: 1, description: "The DSL version; always 1", "x-zh-CN": { description: "DSL 版本，固定为 1" } },
    name: { anyOf: [{ type: "string" }, { type: "string" }], description: "The job name" },
    description: { type: "string", description: "Free text" },
    env: { $ref: "#/$defs/env", description: "Job settings" },
    source: { oneOf: [], description: "The source nodes" },
    transform: { oneOf: [], description: "The transform nodes" },
    sink: { oneOf: [], description: "The sink nodes" },
  },
  $defs: {
    env: {
      properties: {
        mode: {
          anyOf: [{ type: "string", enum: ["batch", "streaming"] }, { type: "string" }],
          examples: ["batch", "streaming"],
          description: "batch or streaming",
        },
        parallelism: { anyOf: [{ type: "integer" }, { type: "string" }], default: 1 },
        source: { type: "object", properties: { rows: { anyOf: [{ type: "integer" }, { type: "string" }] } } },
        transform: { type: "object", properties: {} },
        sink: { type: "object", properties: { limit: { type: "integer" } } },
      },
    },
    sourceNode: {
      properties: {
        id: { description: "The stable id", "x-zh-CN": { description: "稳定 id" } },
        type: { anyOf: [{ type: "string" }, { type: "string" }], examples: ["fake"] },
        plugin: { type: "string" },
        parallelism: { anyOf: [{ type: "integer" }, { type: "string" }] },
      },
      allOf: [
        {
          if: { properties: { type: { const: "fake" } } },
          then: {
            properties: {
              rows: { anyOf: [{ type: "integer" }, { type: "string" }], default: 10, description: "Rows to produce" },
              strict: { anyOf: [{ type: "boolean" }, { type: "string" }] },
              old: { type: "string", deprecated: true },
            },
          },
        },
      ],
    },
    transformNode: {
      properties: { id: {}, type: { examples: ["field-mapper"] }, inputs: { oneOf: [] } },
      allOf: [],
    },
    sinkNode: {
      properties: { id: {}, type: { examples: ["console"] }, inputs: { oneOf: [] } },
    },
  },
};
