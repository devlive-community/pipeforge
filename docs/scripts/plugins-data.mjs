/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Collects the documentation data of every plugin for the generated sections of plugin pages:
// the JSON each plugin module exports and commits (src/generated/plugin-docs/<id>.json, checked
// against the code by the plugin build), its type-mapping.yaml, and its changelog from git.

import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { parse } from "yaml";

/** Where a plugin module keeps its exported documentation data. */
export const PLUGIN_DOCS_DIR = join("src", "generated", "plugin-docs");

/** The JSON layout this site understands (PluginDocs.FORMAT_VERSION of pipeforge-maven-plugin). */
export const PLUGIN_DOCS_FORMAT = 1;

function pluginModules(repoRoot) {
  const modules = [];
  const walk = (dir, depth) => {
    if (depth > 3) return;
    if (existsSync(join(dir, PLUGIN_DOCS_DIR))) modules.push(dir);
    for (const name of readdirSync(dir)) {
      if (name === "node_modules" || name === "target" || name === "src" || name.startsWith(".")) continue;
      const full = join(dir, name);
      if (statSync(full).isDirectory()) walk(full, depth + 1);
    }
  };
  for (const name of readdirSync(repoRoot).sort()) {
    const full = join(repoRoot, name);
    if (name.startsWith("pipeforge-") && statSync(full).isDirectory()) walk(full, 0);
  }
  return modules.sort();
}

/** The commits that changed a module, newest first; empty without git history. */
export function changelog(repoRoot, module) {
  try {
    const out = execFileSync(
      "git",
      ["log", "--no-merges", "--date=short", "--format=%h%x09%ad%x09%s", "--", relative(repoRoot, module) || "."],
      { cwd: repoRoot, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] },
    );
    return out
      .split("\n")
      .filter((line) => line.trim() !== "")
      .map((line) => {
        const [commit, date, ...subject] = line.split("\t");
        return { commit, date, subject: subject.join("\t") };
      });
  } catch {
    return [];
  }
}

/** Every plugin with its exported data, type mapping and changelog, by plugin id. */
export function collectPlugins(repoRoot, { history = true } = {}) {
  const plugins = [];
  for (const module of pluginModules(repoRoot)) {
    const dir = join(module, PLUGIN_DOCS_DIR);
    for (const file of readdirSync(dir).sort()) {
      if (!file.endsWith(".json")) continue;
      const data = JSON.parse(readFileSync(join(dir, file), "utf8"));
      const source = relative(repoRoot, join(dir, file)).split(sep).join("/");
      if (data.format !== PLUGIN_DOCS_FORMAT) {
        throw new Error(`${source}: format ${data.format} is not ${PLUGIN_DOCS_FORMAT}; update the site`);
      }
      const mappingFile = join(module, "src", "main", "plugin", "type-mapping.yaml");
      plugins.push({
        ...data,
        module: relative(repoRoot, module).split(sep).join("/"),
        source,
        typeMapping: existsSync(mappingFile) ? (parse(readFileSync(mappingFile, "utf8")) ?? null) : null,
        changelog: history ? changelog(repoRoot, module) : [],
      });
    }
  }
  return plugins.sort((a, b) => a.id.localeCompare(b.id));
}
