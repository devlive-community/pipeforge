/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

export interface ErrorEntry {
  code: string;
  name: string;
  category: string;
  kind: "TRANSIENT" | "DATA" | "FATAL";
  deprecated: boolean;
  message: Record<string, string>;
  hint: Record<string, string>;
  source: string;
}

function read<T>(file: string, fallback: T): T {
  const path = join(process.cwd(), "generated", file);
  return existsSync(path) ? (JSON.parse(readFileSync(path, "utf8")) as T) : fallback;
}

/** The error registry, exported by `pnpm generate`. */
export function errorEntries(): ErrorEntry[] {
  return read<ErrorEntry[]>("errors.json", []);
}
