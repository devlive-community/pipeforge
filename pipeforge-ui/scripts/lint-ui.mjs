/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// UI rule scan of the Web UI (pnpm lint:ui): only semantic tokens, no dark: variants, no native
// controls, title tooltips or CodeMirror imports outside src/ui. Prints nothing when clean.

import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { scanUi } from "@pipeforge/design-tokens/ui-scan";

/** The component library: the only place for native controls and CodeMirror. */
export const COMPONENT_DIRS = ["src/ui/"];

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const violations = scanUi(root, ["src"], COMPONENT_DIRS);
if (violations.length > 0) {
  for (const violation of violations) console.error(violation);
  console.error(`${violations.length} UI rule violation(s); see development/ui-guide in the documentation.`);
  process.exit(1);
}
