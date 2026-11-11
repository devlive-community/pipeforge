/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Documentation coverage check. For every commit in <base>..<head>:
//   - feat and perf commits, and commits marked as breaking (type!: or BREAKING CHANGE:),
//     that change a code path listed in docs-map.yaml must change every page mapped to
//     that path, in every language (docs/content/<lang>/<page>.mdx);
//   - a commit whose message contains "Docs-Not-Needed: <reason>" is exempt.
// Independently, every module at <head> must be registered in docs-map.yaml.
//
// Usage: node docs/scripts/docs-coverage.mjs <base> <head> [--repo <dir>]

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";
import { LANGUAGES } from "../lib/content-core.mjs";

/** Converts a docs-map glob to a regular expression: ** spans directories, * does not. */
export function globToRegExp(glob) {
  let source = "";
  for (let i = 0; i < glob.length; i += 1) {
    const c = glob[i];
    if (c === "*" && glob[i + 1] === "*") {
      const slash = glob[i + 2] === "/";
      source += slash ? "(?:.*/)?" : ".*";
      i += slash ? 2 : 1;
    } else if (c === "*") {
      source += "[^/]*";
    } else if (c === "?") {
      source += "[^/]";
    } else {
      source += c.replace(/[.+^${}()|[\]\\]/g, "\\$&");
    }
  }
  return new RegExp(`^${source}$`);
}

/** Parses and validates docs-map.yaml. */
export function parseDocsMap(text) {
  const entries = parse(text) ?? [];
  if (!Array.isArray(entries)) throw new Error("docs-map.yaml must be a list");
  return entries.map((entry, index) => {
    const paths = entry?.paths;
    const docs = entry?.docs;
    if (!Array.isArray(paths) || paths.length === 0 || !Array.isArray(docs) || docs.length === 0) {
      throw new Error(`docs-map.yaml entry ${index + 1} needs non-empty 'paths' and 'docs' lists`);
    }
    return { paths, docs, patterns: paths.map(globToRegExp) };
  });
}

const BREAKING = /^BREAKING[ -]CHANGE:/m;

/** Decides whether a commit message needs documentation and whether it opts out. */
export function classifyCommit(message) {
  const subject = message.split("\n", 1)[0] ?? "";
  const header = subject.match(/^(\w+)(?:\([^)]*\))?(!)?:/);
  const type = header?.[1] ?? "";
  const breaking = Boolean(header?.[2]) || BREAKING.test(message);
  const reason = message.match(/^Docs-Not-Needed:[ \t]*(.*)$/m)?.[1]?.trim();
  return {
    subject,
    requiresDocs: type === "feat" || type === "perf" || breaking,
    exempt: reason !== undefined && reason !== "",
    emptyExemption: reason === "",
  };
}

function git(repo, args) {
  return execFileSync("git", args, { cwd: repo, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
}

/** Pages that a set of changed files requires, mapped to the files that require them. */
export function requiredPages(entries, changedFiles) {
  const required = new Map();
  for (const file of changedFiles) {
    if (file.startsWith("docs/content/")) continue;
    for (const entry of entries) {
      if (!entry.patterns.some((p) => p.test(file))) continue;
      for (const page of entry.docs) {
        if (!required.has(page)) required.set(page, file);
      }
    }
  }
  return required;
}

/** Checks the commits of a range; returns a list of problems. */
export function checkCommits(repo, base, head, entries) {
  const problems = [];
  const shas = git(repo, ["rev-list", "--no-merges", "--reverse", `${base}..${head}`])
    .split("\n")
    .filter(Boolean);
  for (const sha of shas) {
    const message = git(repo, ["log", "-1", "--format=%B", sha]);
    const commit = classifyCommit(message);
    const short = sha.slice(0, 12);
    if (commit.emptyExemption) problems.push(`${short} ${commit.subject}: Docs-Not-Needed needs a reason`);
    if (!commit.requiresDocs || commit.exempt) continue;
    const changed = new Set(
      git(repo, ["diff-tree", "--root", "--no-commit-id", "--name-only", "-r", sha]).split("\n").filter(Boolean),
    );
    for (const [page, cause] of requiredPages(entries, changed)) {
      const missing = LANGUAGES.filter((lang) => !changed.has(`docs/content/${lang}/${page}.mdx`));
      if (missing.length > 0) {
        problems.push(
          `${short} ${commit.subject}: changes ${cause} but not the page '${page}' (${missing.join(", ")}); ` +
            "update it or add 'Docs-Not-Needed: <reason>' to the commit message",
        );
      }
    }
  }
  return problems;
}

/** Leaf modules of the repository: pipeforge-* directories and their Maven submodules. */
export function listModules(repo) {
  const files = git(repo, ["ls-files"]).split("\n").filter(Boolean);
  const read = (path) => readFileSync(join(repo, path), "utf8");
  const childrenOf = (dir) => {
    const pom = `${dir}/pom.xml`;
    if (!files.includes(pom)) return [];
    return [...read(pom).matchAll(/<module>\s*([^<\s]+)\s*<\/module>/g)].map(
      (m) => `${dir}/${m[1].replace(/\/$/, "")}`,
    );
  };
  const leaves = [];
  const visit = (dir) => {
    const children = childrenOf(dir).filter((child) => files.some((f) => f.startsWith(`${child}/`)));
    if (children.length === 0) leaves.push(dir);
    else children.forEach(visit);
  };
  const topLevel = new Set(files.map((f) => f.split("/")[0]).filter((d) => d.startsWith("pipeforge-")));
  for (const dir of [...topLevel].sort()) {
    if (files.some((f) => f.startsWith(`${dir}/`))) visit(dir);
  }
  return leaves;
}

/** Modules that no docs-map entry covers. */
export function unregisteredModules(modules, entries) {
  return modules.filter(
    (module) =>
      !entries.some((entry) =>
        entry.paths.some((glob, i) => glob.startsWith(`${module}/`) || entry.patterns[i].test(`${module}/pom.xml`)),
      ),
  );
}

/** Pages listed in docs-map.yaml that do not exist in every language. */
export function missingPages(repo, entries) {
  const missing = [];
  for (const page of new Set(entries.flatMap((entry) => entry.docs))) {
    for (const lang of LANGUAGES) {
      const file = `docs/content/${lang}/${page}.mdx`;
      if (!existsSync(join(repo, file))) missing.push(file);
    }
  }
  return missing;
}

function main(argv) {
  const args = [...argv];
  let repo = process.cwd();
  const repoIndex = args.indexOf("--repo");
  if (repoIndex >= 0) {
    repo = args[repoIndex + 1];
    args.splice(repoIndex, 2);
  }
  const [base, head = "HEAD"] = args;
  if (!base) {
    console.error("usage: check-docs-coverage.sh <base> [<head>]");
    return 2;
  }
  repo = git(repo, ["rev-parse", "--show-toplevel"]).trim();
  const mapFile = join(repo, "docs-map.yaml");
  if (!existsSync(mapFile)) {
    console.error("docs-map.yaml not found");
    return 2;
  }
  let entries;
  try {
    entries = parseDocsMap(readFileSync(mapFile, "utf8"));
  } catch (error) {
    console.error(String(error.message ?? error));
    return 1;
  }
  const problems = checkCommits(repo, base, head, entries);
  for (const module of unregisteredModules(listModules(repo), entries)) {
    problems.push(`module ${module} is not registered in docs-map.yaml`);
  }
  for (const file of missingPages(repo, entries)) {
    problems.push(`docs-map.yaml lists ${file}, which does not exist`);
  }
  if (problems.length > 0) {
    for (const problem of problems) console.log(problem);
    console.error(`${problems.length} documentation coverage problem(s)`);
    return 1;
  }
  console.log("documentation coverage ok");
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)));
}
