/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Reads the OpenAPI document of the server (pipeforge-server/openapi.json), which a server test
// keeps in sync with the code, and reduces it to what the API reference shows: operations with
// their texts in both languages, parameters, request and response schemas, and the fields of
// every schema. Output: generated/api.json.

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

export const DOCUMENT = join("pipeforge-server", "openapi.json");
const METHODS = ["get", "post", "put", "patch", "delete"];
const ZH = "x-zh-CN";

/** A readable type: Name for references, Name[] for arrays, map<string, T>, "A" | "B" for enums. */
export function typeName(schema) {
  if (!schema || Object.keys(schema).length === 0) return "any";
  if (schema.$ref) return schema.$ref.split("/").at(-1);
  if (schema.enum) return schema.enum.map((v) => JSON.stringify(v)).join(" | ");
  const types = (Array.isArray(schema.type) ? schema.type : [schema.type]).filter((t) => t && t !== "null");
  const type = types[0];
  if (type === "array") return `${typeName(schema.items)}[]`;
  if (type === "object" && schema.additionalProperties !== undefined) {
    return `map<string, ${typeName(schema.additionalProperties === true ? {} : schema.additionalProperties)}>`;
  }
  if (type === "string" && schema.format) return `string (${schema.format})`;
  if (type === "integer" && schema.format) return schema.format;
  return type ?? "any";
}

function bodySchema(content) {
  return content?.["application/json"]?.schema ?? content?.["*/*"]?.schema ?? null;
}

/** The operations and schemas of the API, in path order. */
export function collectApi(repoRoot) {
  const file = join(repoRoot, DOCUMENT);
  if (!existsSync(file)) return { operations: [], schemas: [] };
  const document = JSON.parse(readFileSync(file, "utf8"));
  const operations = [];
  for (const [path, item] of Object.entries(document.paths ?? {}).sort(([a], [b]) => a.localeCompare(b))) {
    for (const method of METHODS) {
      const op = item[method];
      if (!op) continue;
      const responses = Object.entries(op.responses ?? {}).map(([status, r]) => ({
        status,
        description: r.description ?? "",
        type: bodySchema(r.content) ? typeName(bodySchema(r.content)) : null,
      }));
      operations.push({
        id: op.operationId,
        method: method.toUpperCase(),
        path,
        tag: op.tags?.[0] ?? "",
        summary: { "en-US": op.summary ?? "", "zh-CN": op[ZH]?.summary ?? op.summary ?? "" },
        description: { "en-US": op.description ?? "", "zh-CN": op[ZH]?.description ?? op.description ?? "" },
        parameters: (op.parameters ?? []).map((p) => ({
          name: p.name,
          in: p.in,
          required: Boolean(p.required),
          type: typeName(p.schema),
          description: p.description ?? "",
        })),
        requestBody: op.requestBody ? typeName(bodySchema(op.requestBody.content)) : null,
        responses,
      });
    }
  }
  const schemas = Object.entries(document.components?.schemas ?? {})
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([name, schema]) => ({
      name,
      fields: Object.entries(schema.properties ?? {}).map(([field, s]) => ({
        name: field,
        type: typeName(s),
        required: (schema.required ?? []).includes(field),
      })),
    }));
  return { operations, schemas };
}
