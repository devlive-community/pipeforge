/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Reads the command reference of the CLI (pipeforge-cli/cli.json), which a CLI test keeps equal to
// the command definitions: commands with their synopsis, description, arguments and options in both
// languages, the global options and the exit codes. Output: generated/cli.json.

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

export const CLI_REFERENCE = join("pipeforge-cli", "cli.json");

/** The CLI reference, or an empty one when the file does not exist. */
export function collectCli(repoRoot) {
  const file = join(repoRoot, CLI_REFERENCE);
  if (!existsSync(file)) return { name: "pipeforge", description: {}, globalOptions: [], commands: [], exitCodes: [] };
  return JSON.parse(readFileSync(file, "utf8"));
}
