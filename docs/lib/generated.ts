/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

export interface ErrorEntry {
  code: string;
  name: string;
  category: string;
  kind: "TRANSIENT" | "DATA" | "FATAL";
  deprecated: boolean;
  message: Record<string, string>;
  hint: Record<string, string>;
  source: string;
}

function read<T>(file: string, fallback: T): T {
  const path = join(process.cwd(), "generated", file);
  return existsSync(path) ? (JSON.parse(readFileSync(path, "utf8")) as T) : fallback;
}

/** The error registry, exported by `pnpm generate`. */
export function errorEntries(): ErrorEntry[] {
  return read<ErrorEntry[]>("errors.json", []);
}

export interface FunctionEntry {
  name: string;
  category: string;
  syntax: string | null;
  deterministic: boolean;
  signatures: string[];
  description: Record<string, string>;
  nulls: Record<string, string> | null;
  examples: { expression: string; result: string }[];
}

/** The built-in function catalog, exported by `pnpm generate`. */
export function functionEntries(): FunctionEntry[] {
  return read<FunctionEntry[]>("functions.json", []);
}

export interface ApiOperation {
  id: string;
  method: string;
  path: string;
  tag: string;
  summary: Record<string, string>;
  description: Record<string, string>;
  parameters: { name: string; in: string; required: boolean; type: string; description: string }[];
  requestBody: string | null;
  responses: { status: string; description: string; type: string | null }[];
}

export interface ApiSchema {
  name: string;
  fields: { name: string; type: string; required: boolean }[];
}

/** The REST API from the server's OpenAPI document, exported by `pnpm generate`. */
export function apiReference(): { operations: ApiOperation[]; schemas: ApiSchema[] } {
  return read("api.json", { operations: [], schemas: [] });
}
