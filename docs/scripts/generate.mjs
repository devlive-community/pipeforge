/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Collects the generated data the site needs before it is built: the search documents for
// each language (public/search/<lang>.json). Data exported from the code (plugins, errors,
// functions, metrics, CLI) is added to docs/generated/ by the build steps of those modules.

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { LANGUAGES, loadPages } from "../lib/content-core.mjs";
import { buildSearchDocuments } from "./search-index.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const pages = loadPages(join(root, "content"));
const outDir = join(root, "public", "search");
mkdirSync(outDir, { recursive: true });
mkdirSync(join(root, "generated"), { recursive: true });

for (const lang of LANGUAGES) {
  const documents = buildSearchDocuments(pages, lang);
  writeFileSync(join(outDir, `${lang}.json`), JSON.stringify(documents));
  console.log(`search index ${lang}: ${documents.length} page(s)`);
}
