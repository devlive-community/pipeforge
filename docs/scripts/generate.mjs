/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Collects the generated data the site needs before it is built: the search documents for
// each language (public/search/<lang>.json) and the reference data read from the code
// (generated/errors.json from the error registry). Data exported by Maven builds (plugins,
// functions, metrics, CLI) is added to docs/generated/ by the build steps of those modules.

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { LANGUAGES, loadPages } from "../lib/content-core.mjs";
import { collectErrors } from "./errors-data.mjs";
import { buildSearchDocuments } from "./search-index.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const pages = loadPages(join(root, "content"));
const outDir = join(root, "public", "search");
mkdirSync(outDir, { recursive: true });
mkdirSync(join(root, "generated"), { recursive: true });

const errors = collectErrors(join(root, ".."));
writeFileSync(join(root, "generated", "errors.json"), JSON.stringify(errors, null, 2));
console.log(`error registry: ${errors.length} code(s)`);

for (const lang of LANGUAGES) {
  const documents = buildSearchDocuments(pages, lang);
  writeFileSync(join(outDir, `${lang}.json`), JSON.stringify(documents));
  console.log(`search index ${lang}: ${documents.length} page(s)`);
}
