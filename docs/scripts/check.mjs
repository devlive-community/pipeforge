/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Runs the documentation checks. Usage: node scripts/check.mjs [structure|i18n|links|ui|plugins ...]
// Exits with 1 and prints every problem when a check fails.

import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { loadPages } from "../lib/content-core.mjs";
import { checkI18n } from "./checks/i18n.mjs";
import { checkLinks } from "./checks/links.mjs";
import { checkPlugins } from "./checks/plugins.mjs";
import { checkStructure } from "./checks/structure.mjs";
import { scanUi } from "./checks/ui-scan.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const pages = loadPages(join(root, "content"));

// Anchors of pages that render generated data (run `pnpm generate` first).
const errorsFile = join(root, "generated", "errors.json");
const functionsFile = join(root, "generated", "functions.json");
const apiFile = join(root, "generated", "api.json");
const metricsFile = join(root, "generated", "metrics.json");
const pluginsFile = join(root, "generated", "plugins.json");
const api = existsSync(apiFile) ? JSON.parse(readFileSync(apiFile, "utf8")) : { operations: [], schemas: [] };
const generatedAnchors = {
  errors: existsSync(errorsFile) ? JSON.parse(readFileSync(errorsFile, "utf8")).map((e) => e.code.toLowerCase()) : [],
  functions: existsSync(functionsFile)
    ? JSON.parse(readFileSync(functionsFile, "utf8")).map((f) => f.name.toLowerCase())
    : [],
  metrics: existsSync(metricsFile)
    ? JSON.parse(readFileSync(metricsFile, "utf8")).map((m) => `${m.scope}-${m.name}`.toLowerCase())
    : [],
  api: [...api.operations.map((o) => o.id.toLowerCase()), ...api.schemas.map((s) => `schema-${s.name.toLowerCase()}`)],
};

const checks = {
  structure: () => checkStructure(pages),
  i18n: () => checkI18n(pages),
  links: () => checkLinks(pages, generatedAnchors),
  ui: () => scanUi(root),
  plugins: () => checkPlugins(pages, existsSync(pluginsFile) ? JSON.parse(readFileSync(pluginsFile, "utf8")) : null),
};

const selected = process.argv.slice(2);
for (const name of selected) {
  if (!(name in checks)) {
    console.error(`unknown check '${name}' (available: ${Object.keys(checks).join(", ")})`);
    process.exit(2);
  }
}

let failed = 0;
for (const name of selected.length > 0 ? selected : Object.keys(checks)) {
  const errors = checks[name]();
  if (errors.length === 0) {
    console.log(`ok    ${name}`);
  } else {
    failed += errors.length;
    console.log(`FAIL  ${name}`);
    for (const error of errors) console.log(`      ${error}`);
  }
}
if (failed > 0) {
  console.error(`${failed} problem(s) found`);
  process.exit(1);
}
console.log(`${pages.length} page(s) checked`);
