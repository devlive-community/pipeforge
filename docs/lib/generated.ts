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

export interface MetricEntry {
  name: string;
  scope: "job" | "node";
  type: string;
  unit: string;
  description: Record<string, string>;
}

/** The metric catalog of the engine, exported by `pnpm generate`. */
export function metricEntries(): MetricEntry[] {
  return read<MetricEntry[]>("metrics.json", []);
}

export interface PluginOption {
  key: string;
  type: string;
  requirement: "required" | "optional" | "one-of" | "conditional";
  default: unknown;
  description: string;
  group: string | null;
  min: number | null;
  max: number | null;
  values: string[];
  sensitive: boolean;
  deprecatedKeys: string[];
  since: string | null;
}

export interface PluginComponent {
  type: string;
  identifier: string;
  factory: string;
  capabilities: string[];
  options?: PluginOption[];
  exclusive?: string[][];
  conditions?: { option: string; value: unknown; required: string[] }[];
  nativeNamespaces?: string[];
}

export interface TypeMappingEntry {
  logical?: string;
  external?: string;
  note?: string;
}

export interface PluginEntry {
  id: string;
  name: string;
  version: string;
  description: string;
  datasource?: { type: string; versions: string[] };
  components: PluginComponent[];
  module: string;
  source: string;
  typeMapping: { read?: TypeMappingEntry[]; write?: TypeMappingEntry[] } | null;
  changelog: { commit: string; date: string; subject: string }[];
}

/** Every plugin's documentation data, exported by `pnpm generate`. */
export function pluginEntries(): PluginEntry[] {
  return read<PluginEntry[]>("plugins.json", []);
}

export function pluginEntry(id: string): PluginEntry | undefined {
  return pluginEntries().find((plugin) => plugin.id === id);
}
