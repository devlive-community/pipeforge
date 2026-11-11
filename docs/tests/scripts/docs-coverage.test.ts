/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// @vitest-environment node

import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  checkCommits,
  classifyCommit,
  globToRegExp,
  listModules,
  missingPages,
  parseDocsMap,
  requiredPages,
  unregisteredModules,
} from "@/scripts/docs-coverage.mjs";

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), "../../scripts/check-docs-coverage.sh");

const DOCS_MAP = `
- paths: ["pipeforge-cli/**"]
  docs: ["reference/cli"]
- paths: ["pipeforge-engine/engine-worker/src/main/**"]
  docs: ["concepts/execution", "reference/metrics"]
`;

let repo: string;

function git(...args: string[]) {
  return execFileSync("git", args, { cwd: repo, encoding: "utf8" }).trim();
}

function write(path: string, content = "x") {
  mkdirSync(dirname(join(repo, path)), { recursive: true });
  writeFileSync(join(repo, path), content);
}

/** Writes files and commits them with the given message; returns the commit id. */
function commit(message: string, files: string[]) {
  for (const file of files) write(file, `${file} ${Math.random()}`);
  git("add", "-A");
  git("commit", "-q", "-m", message);
  return git("rev-parse", "HEAD");
}

function bothLangs(page: string) {
  return [`docs/content/en-US/${page}.mdx`, `docs/content/zh-CN/${page}.mdx`];
}

beforeEach(() => {
  repo = mkdtempSync(join(tmpdir(), "docs-coverage-"));
  git("init", "-q", "-b", "main");
  git("config", "user.name", "Test");
  git("config", "user.email", "test@example.org");
  write("docs-map.yaml", DOCS_MAP);
  write("pipeforge-cli/pom.xml", "<project/>");
  write("pipeforge-engine/pom.xml", "<project><modules><module>engine-worker</module></modules></project>");
  write("pipeforge-engine/engine-worker/pom.xml", "<project/>");
  for (const page of ["reference/cli", "concepts/execution", "reference/metrics"]) {
    for (const file of bothLangs(page)) write(file);
  }
  git("add", "-A");
  git("commit", "-q", "-m", "chore: base");
});

afterEach(() => rmSync(repo, { recursive: true, force: true }));

const entries = () => parseDocsMap(DOCS_MAP);

describe("globToRegExp", () => {
  it("matches ** across directories and * within one", () => {
    expect(globToRegExp("pipeforge-cli/**").test("pipeforge-cli/src/main/A.java")).toBe(true);
    expect(globToRegExp("a/**/b.txt").test("a/b.txt")).toBe(true);
    expect(globToRegExp("a/**/b.txt").test("a/x/y/b.txt")).toBe(true);
    expect(globToRegExp("a/*.txt").test("a/x/b.txt")).toBe(false);
    expect(globToRegExp("pipeforge-cli/**").test("pipeforge-cli-extra/A.java")).toBe(false);
  });
});

describe("classifyCommit", () => {
  it("requires docs for feat, perf and breaking commits", () => {
    expect(classifyCommit("feat(cli): add run").requiresDocs).toBe(true);
    expect(classifyCommit("perf(engine): faster").requiresDocs).toBe(true);
    expect(classifyCommit("refactor(core)!: rename").requiresDocs).toBe(true);
    expect(classifyCommit("fix(core): x\n\nBREAKING CHANGE: y").requiresDocs).toBe(true);
    expect(classifyCommit("fix(core): x").requiresDocs).toBe(false);
    expect(classifyCommit("test(core): x").requiresDocs).toBe(false);
  });

  it("recognizes the Docs-Not-Needed trailer and rejects an empty reason", () => {
    expect(classifyCommit("feat(cli): x\n\nDocs-Not-Needed: internal flag").exempt).toBe(true);
    const empty = classifyCommit("feat(cli): x\n\nDocs-Not-Needed:");
    expect(empty.exempt).toBe(false);
    expect(empty.emptyExemption).toBe(true);
  });
});

describe("docs-map.yaml", () => {
  it("rejects entries without paths or docs", () => {
    expect(() => parseDocsMap('- paths: ["a/**"]\n')).toThrow(/entry 1 needs/);
    expect(() => parseDocsMap("[]")).not.toThrow();
  });

  it("maps changed files to required pages, ignoring the content itself", () => {
    const required = requiredPages(entries(), [
      "pipeforge-engine/engine-worker/src/main/Task.java",
      "docs/content/x.mdx",
    ]);
    expect([...required.keys()]).toEqual(["concepts/execution", "reference/metrics"]);
  });
});

describe("checkCommits", () => {
  it("accepts a feature commit that updates its pages in both languages", () => {
    const base = git("rev-parse", "HEAD");
    commit("feat(cli): add version", ["pipeforge-cli/src/Main.java", ...bothLangs("reference/cli")]);
    expect(checkCommits(repo, base, "HEAD", entries())).toEqual([]);
  });

  it("reports a feature commit without documentation", () => {
    const base = git("rev-parse", "HEAD");
    commit("feat(cli): add version", ["pipeforge-cli/src/Main.java"]);
    const problems = checkCommits(repo, base, "HEAD", entries());
    expect(problems).toHaveLength(1);
    expect(problems[0]).toMatch(
      /feat\(cli\): add version: changes pipeforge-cli\/src\/Main.java but not the page 'reference\/cli' \(en-US, zh-CN\)/,
    );
  });

  it("requires every language and every mapped page", () => {
    const base = git("rev-parse", "HEAD");
    commit("perf(engine-worker): batch reads", [
      "pipeforge-engine/engine-worker/src/main/Task.java",
      "docs/content/en-US/concepts/execution.mdx",
      ...bothLangs("reference/metrics"),
    ]);
    const problems = checkCommits(repo, base, "HEAD", entries());
    expect(problems).toEqual([expect.stringMatching(/'concepts\/execution' \(zh-CN\)/)]);
  });

  it("skips exempt commits and commits that need no docs", () => {
    const base = git("rev-parse", "HEAD");
    commit("feat(cli): internal switch\n\nDocs-Not-Needed: hidden developer flag", ["pipeforge-cli/src/A.java"]);
    commit("fix(cli): typo", ["pipeforge-cli/src/B.java"]);
    commit("test(engine-worker): more cases", ["pipeforge-engine/engine-worker/src/main/C.java"]);
    expect(checkCommits(repo, base, "HEAD", entries())).toEqual([]);
  });

  it("checks breaking changes of any type", () => {
    const base = git("rev-parse", "HEAD");
    commit("refactor(cli)!: rename option", ["pipeforge-cli/src/A.java"]);
    expect(checkCommits(repo, base, "HEAD", entries())).toHaveLength(1);
  });

  it("ignores changes outside mapped paths and reports empty exemptions", () => {
    const base = git("rev-parse", "HEAD");
    commit("feat(tools): add script", ["tools/x.sh"]);
    commit("feat(cli): y\n\nDocs-Not-Needed:", ["tools/y.sh"]);
    expect(checkCommits(repo, base, "HEAD", entries())).toEqual([expect.stringMatching(/needs a reason/)]);
  });
});

describe("module registration", () => {
  it("lists leaf modules and finds unregistered ones", () => {
    write("pipeforge-api/pom.xml", "<project/>");
    git("add", "-A");
    git("commit", "-q", "-m", "feat(api): add module");
    expect(listModules(repo)).toEqual(["pipeforge-api", "pipeforge-cli", "pipeforge-engine/engine-worker"]);
    expect(unregisteredModules(listModules(repo), entries())).toEqual(["pipeforge-api"]);
  });

  it("reports mapped pages that do not exist", () => {
    rmSync(join(repo, "docs/content/zh-CN/reference/cli.mdx"));
    expect(missingPages(repo, entries())).toEqual(["docs/content/zh-CN/reference/cli.mdx"]);
  });
});

describe("check-docs-coverage.sh", () => {
  function run(base: string) {
    try {
      return { status: 0, out: execFileSync("bash", [SCRIPT, base, "HEAD"], { cwd: repo, encoding: "utf8" }) };
    } catch (error) {
      const e = error as { status: number; stdout: string };
      return { status: e.status, out: e.stdout };
    }
  }

  it("exits 0 when coverage holds and 1 with the problems otherwise", () => {
    const base = git("rev-parse", "HEAD");
    commit("feat(cli): add version", ["pipeforge-cli/src/Main.java", ...bothLangs("reference/cli")]);
    expect(run(base)).toMatchObject({ status: 0 });

    commit("feat(cli): add doctor", ["pipeforge-cli/src/Doctor.java"]);
    write("pipeforge-new/pom.xml", "<project/>");
    git("add", "-A");
    git("commit", "-q", "-m", "chore: add unmapped module");
    const result = run(base);
    expect(result.status).toBe(1);
    expect(result.out).toMatch(/feat\(cli\): add doctor/);
    expect(result.out).toMatch(/module pipeforge-new is not registered/);
  });

  it("prints usage without arguments", () => {
    expect(() => execFileSync("bash", [SCRIPT], { cwd: repo, stdio: "pipe" })).toThrow();
  });
});
