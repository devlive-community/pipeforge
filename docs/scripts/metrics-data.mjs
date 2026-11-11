/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";

/** The metric catalog of the engine, read for the generated metrics reference. */
export const METRIC_CATALOG = join(
  "pipeforge-engine",
  "engine-api",
  "src",
  "main",
  "resources",
  "metrics",
  "catalog.yaml",
);

const SCOPE_ORDER = ["job", "node"];

/** Every metric of the catalog, job metrics first, each scope in catalog order. */
export function collectMetrics(repoRoot) {
  const file = join(repoRoot, METRIC_CATALOG);
  if (!existsSync(file)) return [];
  const entries = parse(readFileSync(file, "utf8")) ?? [];
  return entries
    .map((entry, index) => ({
      name: String(entry.name),
      scope: String(entry.scope),
      type: String(entry.type),
      unit: String(entry.unit),
      description: entry.description ?? {},
      index,
    }))
    .sort((a, b) => SCOPE_ORDER.indexOf(a.scope) - SCOPE_ORDER.indexOf(b.scope) || a.index - b.index)
    .map(({ index: _index, ...metric }) => metric);
}
