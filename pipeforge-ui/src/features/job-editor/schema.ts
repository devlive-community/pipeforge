/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Language } from "@/i18n";
import type { NodeKind } from "./structure";

/** What completion offers for one key. */
export interface Field {
  key: string;
  /** The JSON type, shown next to the key. */
  detail: string;
  description: string;
  values: string[];
  defaultValue?: unknown;
  deprecated: boolean;
}

/** The job schema reduced to what completion needs. */
export interface SchemaInfo {
  root: Field[];
  env: Field[];
  envDefaults: Record<NodeKind, Field[]>;
  node: Record<NodeKind, Field[]>;
  byType: Record<NodeKind, Record<string, Field[]>>;
  types: Record<NodeKind, string[]>;
}

type JsonSchema = Record<string, unknown>;

const asSchema = (value: unknown): JsonSchema => (value && typeof value === "object" ? (value as JsonSchema) : {});

/** The branch of an anyOf that is not the variable branch, where types and choices live. */
function main(schema: JsonSchema): JsonSchema {
  const anyOf = schema.anyOf;
  return Array.isArray(anyOf) && anyOf.length > 0 ? { ...asSchema(anyOf[0]), ...schema } : schema;
}

function typeText(schema: JsonSchema): string {
  const type = main(schema).type;
  if (Array.isArray(type)) return type.join(" | ");
  if (typeof type === "string") return type;
  if ("const" in schema) return String(schema.const);
  return Array.isArray(schema.oneOf) ? "list" : "";
}

function field(key: string, value: unknown, language: Language): Field {
  const schema = asSchema(value);
  const merged = main(schema);
  const zh = asSchema(schema["x-zh-CN"]).description;
  const values = Array.isArray(schema.examples)
    ? schema.examples.map(String)
    : Array.isArray(merged.enum)
      ? merged.enum.map(String)
      : merged.type === "boolean"
        ? ["true", "false"]
        : "const" in schema
          ? [String(schema.const)]
          : [];
  return {
    key,
    detail: typeText(schema),
    description: String(language === "zh-CN" && typeof zh === "string" ? zh : (schema.description ?? "")),
    values,
    defaultValue: schema.default,
    deprecated: schema.deprecated === true,
  };
}

function fields(properties: unknown, language: Language): Field[] {
  return Object.entries(asSchema(properties)).map(([key, value]) => field(key, value, language));
}

/** Reads the schema served by GET /api/v1/jobs/schema. */
export function readSchema(schema: unknown, language: Language): SchemaInfo {
  const root = asSchema(schema);
  const defs = asSchema(root.$defs);
  const env = asSchema(defs.env);
  const envProperties = asSchema(env.properties);
  const info: SchemaInfo = {
    root: fields(root.properties, language),
    env: fields(envProperties, language),
    envDefaults: { source: [], transform: [], sink: [] },
    node: { source: [], transform: [], sink: [] },
    byType: { source: {}, transform: {}, sink: {} },
    types: { source: [], transform: [], sink: [] },
  };
  for (const kind of ["source", "transform", "sink"] as const) {
    info.envDefaults[kind] = fields(asSchema(envProperties[kind]).properties, language);
    const node = asSchema(defs[`${kind}Node`]);
    const properties = asSchema(node.properties);
    info.node[kind] = fields(properties, language);
    const examples = asSchema(properties.type).examples;
    info.types[kind] = Array.isArray(examples) ? examples.map(String) : [];
    for (const branch of Array.isArray(node.allOf) ? node.allOf : []) {
      const b = asSchema(branch);
      const type = asSchema(asSchema(asSchema(b.if).properties).type).const;
      if (typeof type === "string") info.byType[kind][type] = fields(asSchema(b.then).properties, language);
    }
  }
  return info;
}
