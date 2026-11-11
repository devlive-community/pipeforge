/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PLUGIN_SECTIONS } from "@/lib/content-core.mjs";
import { checkPlugins, sectionBodies } from "@/scripts/checks/plugins.mjs";
import { changelog, collectPlugins } from "@/scripts/plugins-data.mjs";
import { page } from "./fixtures";

const plugin = { title: "Fake", description: "D", order: 1, template: "plugin" };

const body = (generated: Record<string, string> = {}) =>
  PLUGIN_SECTIONS.map((s) => {
    const component = {
      capabilities: '<PluginCapabilities plugin="fake" component="source" />',
      versions: '<PluginVersions plugin="fake" />',
      options: '<PluginOptions plugin="fake" component="source" />',
      "native-options": '<PluginNativeOptions plugin="fake" />',
      "type-mapping": '<PluginTypeMapping plugin="fake" />',
      changelog: '<PluginChangelog plugin="fake" />',
    }[s.key];
    return `## ${s["en-US"]}\n\n${generated[s.key] ?? component ?? "text"}\n`;
  }).join("\n");

const data = [{ id: "fake", components: [{ type: "source" }] }];

describe("plugin page check", () => {
  it("accepts generated sections with only their component", () => {
    expect(checkPlugins([page("en-US", "plugins/source/fake", plugin, body())], data)).toEqual([]);
    expect(checkPlugins([page("en-US", "plugins/source/fake", plugin, body())], null)).toEqual([]);
    expect(checkPlugins([page("en-US", "concepts/x", { title: "T", description: "D" }, "## A\n\ntext")], data)).toEqual(
      [],
    );
  });

  it("rejects hand-written content, wrong components and wrong plugins", () => {
    const errors = checkPlugins(
      [
        page(
          "en-US",
          "plugins/source/fake",
          plugin,
          body({
            versions: "Works with everything.",
            options: '<PluginVersions plugin="fake" />',
            changelog: '<PluginChangelog plugin="other" />',
            "native-options": '<PluginNativeOptions plugin="fake" />\n\nMore text.',
          }),
        ),
      ],
      data,
    ).join("\n");
    expect(errors).toMatch(/'Verified versions' is generated; it must contain only <PluginVersions plugin="fake" \/>/);
    expect(errors).toMatch(/'Node options' is generated; it must contain only <PluginOptions/);
    expect(errors).toMatch(/'Native options' is generated/);
    expect(errors).toMatch(/<PluginChangelog> names plugin 'other' but the page is for 'fake'/);
  });

  it("requires documentation data for the plugin and its component", () => {
    const errors = checkPlugins(
      [
        page(
          "en-US",
          "plugins/source/fake",
          plugin,
          body({ capabilities: '<PluginCapabilities plugin="fake" component="sink" />' }),
        ),
      ],
      [],
    ).join("\n");
    expect(errors).toMatch(/plugin 'fake' has no documentation data \(src\/generated\/plugin-docs\/fake.json\)/);
    expect(
      checkPlugins(
        [
          page(
            "en-US",
            "plugins/source/fake",
            plugin,
            body({ capabilities: '<PluginCapabilities plugin="fake" component="sink" />' }),
          ),
        ],
        data,
      ),
    ).toEqual(["en-US/plugins/source/fake: plugin 'fake' has no sink component"]);
  });

  it("splits a body into sections outside code fences", () => {
    const sections = sectionBodies("intro\n## A\n\none\n```\n## not a heading\n```\n## B\n");
    expect([...sections.keys()]).toEqual(["A", "B"]);
    expect(sections.get("A")).toEqual(["one", "```", "## not a heading", "```"]);
    expect(sections.get("B")).toEqual([]);
  });
});

describe("plugin data", () => {
  it("collects the committed data of every plugin module with its type mapping", () => {
    const root = mkdtempSync(join(tmpdir(), "plugins-"));
    const module = join(root, "pipeforge-connectors", "connector-fake");
    mkdirSync(join(module, "src", "generated", "plugin-docs"), { recursive: true });
    mkdirSync(join(module, "src", "main", "plugin"), { recursive: true });
    mkdirSync(join(root, "pipeforge-connectors", "node_modules", "x"), { recursive: true });
    writeFileSync(
      join(module, "src", "generated", "plugin-docs", "fake.json"),
      JSON.stringify({ format: 1, id: "fake", components: [] }),
    );
    writeFileSync(
      join(module, "src", "main", "plugin", "type-mapping.yaml"),
      "read:\n  - { external: INT, logical: INT32 }\n",
    );
    writeFileSync(join(module, "src", "generated", "plugin-docs", "README.txt"), "ignored");

    const plugins = collectPlugins(root, { history: false });
    expect(plugins).toHaveLength(1);
    expect(plugins[0]).toMatchObject({
      id: "fake",
      module: "pipeforge-connectors/connector-fake",
      source: "pipeforge-connectors/connector-fake/src/generated/plugin-docs/fake.json",
      typeMapping: { read: [{ external: "INT", logical: "INT32" }] },
      changelog: [],
    });

    writeFileSync(
      join(module, "src", "generated", "plugin-docs", "fake.json"),
      JSON.stringify({ format: 2, id: "fake" }),
    );
    expect(() => collectPlugins(root)).toThrow(/format 2 is not 1/);
  });

  it("reads the changelog of a module from git, newest first", () => {
    const root = mkdtempSync(join(tmpdir(), "changelog-"));
    const git = (...args: string[]) => execFileSync("git", args, { cwd: root, stdio: "ignore" });
    git("init", "-q");
    git("config", "user.email", "t@example.org");
    git("config", "user.name", "t");
    mkdirSync(join(root, "m"));
    writeFileSync(join(root, "m", "a.txt"), "1");
    git("add", ".");
    git("commit", "-q", "-m", "feat(m): add m");
    writeFileSync(join(root, "other.txt"), "1");
    git("add", ".");
    git("commit", "-q", "-m", "chore: unrelated");
    writeFileSync(join(root, "m", "a.txt"), "2");
    git("add", ".");
    git("commit", "-q", "-m", "fix(m): fix m");

    expect(changelog(root, join(root, "m")).map((e: { subject: string }) => e.subject)).toEqual([
      "fix(m): fix m",
      "feat(m): add m",
    ]);
    expect(changelog(join(tmpdir(), "not-a-repo-" + Date.now()), "x")).toEqual([]);
  });
});
