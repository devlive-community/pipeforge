/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Collects the error registry for the documentation: the core files in pipeforge-common and the
// META-INF/pipeforge/errors.yaml of every plugin module. Output: generated/errors.json.

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { parse } from "yaml";

const CATEGORY_ORDER = [
  "CONF",
  "PLAN",
  "PLUG",
  "CONN",
  "AUTH",
  "META",
  "DATA",
  "SRC",
  "SINK",
  "CKPT",
  "RES",
  "CLUS",
  "SYS",
];
const DEFAULT_KIND = { CONN: "TRANSIENT", DATA: "DATA", RES: "TRANSIENT", CLUS: "TRANSIENT" };

function registryFiles(repoRoot) {
  const files = [];
  const core = join(repoRoot, "pipeforge-common", "src", "main", "resources", "errors");
  if (existsSync(core)) {
    for (const name of readdirSync(core).sort()) if (name.endsWith(".yaml")) files.push(join(core, name));
  }
  const walk = (dir, depth) => {
    if (depth > 6) return;
    for (const name of readdirSync(dir)) {
      if (name === "node_modules" || name === "target" || name.startsWith(".")) continue;
      const full = join(dir, name);
      if (!statSync(full).isDirectory()) continue;
      const plugin = join(full, "src", "main", "resources", "META-INF", "pipeforge", "errors.yaml");
      if (existsSync(plugin)) files.push(plugin);
      walk(full, depth + 1);
    }
  };
  for (const name of readdirSync(repoRoot)) {
    if (name.startsWith("pipeforge-") && statSync(join(repoRoot, name)).isDirectory()) walk(join(repoRoot, name), 0);
  }
  return [...new Set(files)];
}

/** Reads every registry file into one list sorted by category and code. */
export function collectErrors(repoRoot) {
  const errors = [];
  for (const file of registryFiles(repoRoot)) {
    const entries = parse(readFileSync(file, "utf8")) ?? [];
    for (const entry of entries) {
      const category = String(entry.code).split("-")[1];
      errors.push({
        code: entry.code,
        name: entry.name,
        category,
        kind: String(entry.kind ?? DEFAULT_KIND[category] ?? "FATAL").toUpperCase(),
        deprecated: entry.deprecated === true,
        message: entry.message,
        hint: entry.hint,
        source: relative(repoRoot, file).split(sep).join("/"),
      });
    }
  }
  return errors.sort(
    (a, b) => CATEGORY_ORDER.indexOf(a.category) - CATEGORY_ORDER.indexOf(b.category) || a.code.localeCompare(b.code),
  );
}
