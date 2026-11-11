/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Runs the documentation checks. Usage: node scripts/check.mjs [structure|i18n|links|ui ...]
// Exits with 1 and prints every problem when a check fails.

import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { loadPages } from "../lib/content-core.mjs";
import { checkI18n } from "./checks/i18n.mjs";
import { checkLinks } from "./checks/links.mjs";
import { checkStructure } from "./checks/structure.mjs";
import { scanUi } from "./checks/ui-scan.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const pages = loadPages(join(root, "content"));

const checks = {
  structure: () => checkStructure(pages),
  i18n: () => checkI18n(pages),
  links: () => checkLinks(pages),
  ui: () => scanUi(root),
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
