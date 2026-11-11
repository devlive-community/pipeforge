/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Generates src/schema.ts from the OpenAPI document of the server (pipeforge-server/openapi.json).
// With --check it only verifies that the committed file is up to date.

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import openapiTS, { astToString } from "openapi-typescript";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const documentPath = join(root, "..", "pipeforge-server", "openapi.json");
const target = join(root, "src", "schema.ts");

const HEADER = `/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Generated from pipeforge-server/openapi.json by scripts/generate.mjs; do not edit.
// Regenerate with: pnpm --filter @pipeforge/client generate

`;

const document = JSON.parse(readFileSync(documentPath, "utf8"));
const ast = await openapiTS(document, { alphabetize: true });
const generated = HEADER + astToString(ast);

if (process.argv.includes("--check")) {
  const committed = readFileSync(target, "utf8");
  if (committed !== generated) {
    console.error("src/schema.ts is out of date; run: pnpm --filter @pipeforge/client generate");
    process.exit(1);
  }
  console.log("src/schema.ts is up to date");
} else {
  writeFileSync(target, generated);
  console.log(`wrote ${target}`);
}
