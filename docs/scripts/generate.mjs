/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Collects the generated data the site needs before it is built: the search documents for
// each language (public/search/<lang>.json) and the reference data read from the code
// (generated/errors.json from the error registry, generated/functions.json from the function
// catalog, generated/api.json from the server's OpenAPI document). Data exported by Maven builds (plugins,
// functions, metrics, CLI) is added to docs/generated/ by the build steps of those modules.

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { LANGUAGES, loadPages } from "../lib/content-core.mjs";
import { collectApi } from "./api-data.mjs";
import { collectErrors } from "./errors-data.mjs";
import { collectFunctions } from "./functions-data.mjs";
import { collectMetrics } from "./metrics-data.mjs";
import { collectPlugins } from "./plugins-data.mjs";
import { buildSearchDocuments } from "./search-index.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const pages = loadPages(join(root, "content"));
const outDir = join(root, "public", "search");
mkdirSync(outDir, { recursive: true });
mkdirSync(join(root, "generated"), { recursive: true });

const errors = collectErrors(join(root, ".."));
writeFileSync(join(root, "generated", "errors.json"), JSON.stringify(errors, null, 2));
console.log(`error registry: ${errors.length} code(s)`);

const functions = collectFunctions(join(root, ".."));
writeFileSync(join(root, "generated", "functions.json"), JSON.stringify(functions, null, 2));
console.log(`function catalog: ${functions.length} function(s)`);

const metrics = collectMetrics(join(root, ".."));
writeFileSync(join(root, "generated", "metrics.json"), JSON.stringify(metrics, null, 2));
console.log(`metric catalog: ${metrics.length} metric(s)`);

const plugins = collectPlugins(join(root, ".."));
writeFileSync(join(root, "generated", "plugins.json"), JSON.stringify(plugins, null, 2));
console.log(`plugin docs: ${plugins.length} plugin(s)`);

const api = collectApi(join(root, ".."));
writeFileSync(join(root, "generated", "api.json"), JSON.stringify(api, null, 2));
console.log(`API: ${api.operations.length} operation(s), ${api.schemas.length} schema(s)`);

for (const lang of LANGUAGES) {
  const documents = buildSearchDocuments(pages, lang);
  writeFileSync(join(outDir, `${lang}.json`), JSON.stringify(documents));
  console.log(`search index ${lang}: ${documents.length} page(s)`);
}
