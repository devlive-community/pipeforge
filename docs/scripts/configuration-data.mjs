/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Reads the configuration reference of the cluster processes (pipeforge-dist/configuration.json),
// which a test of pipeforge-dist keeps equal to the settings in the code. Output:
// generated/configuration.json.

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

export const CONFIGURATION_REFERENCE = join("pipeforge-dist", "configuration.json");

/** Every setting, or none when the file does not exist. */
export function collectConfiguration(repoRoot) {
  const file = join(repoRoot, CONFIGURATION_REFERENCE);
  if (!existsSync(file)) return [];
  return JSON.parse(readFileSync(file, "utf8")).settings ?? [];
}
