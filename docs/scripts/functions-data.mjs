/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Reads the built-in function catalog (pipeforge-expressions/src/main/resources/functions/builtin.yaml).
// The Java build checks every signature and example in it; the site renders it as the function
// reference. Output: generated/functions.json.

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";

export const CATALOG = join("pipeforge-expressions", "src", "main", "resources", "functions", "builtin.yaml");
export const CATEGORIES = ["comparison", "numeric", "string", "datetime", "conversion"];

/** The functions sorted by category and name. */
export function collectFunctions(repoRoot) {
  const file = join(repoRoot, CATALOG);
  if (!existsSync(file)) return [];
  const entries = parse(readFileSync(file, "utf8")) ?? [];
  return entries
    .map((entry) => ({
      name: entry.name,
      category: entry.category,
      syntax: entry.syntax ?? null,
      deterministic: entry.deterministic !== false,
      signatures: entry.signatures ?? [],
      description: entry.description ?? {},
      nulls: entry.nulls ?? null,
      examples: entry.examples ?? [],
    }))
    .sort((a, b) => CATEGORIES.indexOf(a.category) - CATEGORIES.indexOf(b.category) || a.name.localeCompare(b.name));
}
