/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Runs Lighthouse CI against the static export. When the site is built for a sub-path
// (DOCS_BASE_PATH=/pipeforge), the export is served below that path so that asset URLs
// resolve exactly as they do on GitHub Pages.

import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const base = (process.env.DOCS_BASE_PATH ?? "").replace(/\/$/, "");
const siteDir = join(root, ".lighthouseci", "site");

rmSync(siteDir, { recursive: true, force: true });
mkdirSync(join(siteDir, base), { recursive: true });
cpSync(join(root, "out"), join(siteDir, base), { recursive: true });

const urls = ["/en-US/", "/zh-CN/", "/en-US/next/getting-started/overview/"].map((u) => base + u);
const args = ["autorun", `--collect.staticDistDir=${siteDir}`, ...urls.map((u) => `--collect.url=${u}`)];
const result = spawnSync("lhci", args, { cwd: root, stdio: "inherit", shell: process.platform === "win32" });
process.exit(result.status ?? 1);
