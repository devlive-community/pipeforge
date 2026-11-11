/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Plugin pages: the generated sections contain only the component that renders them, for the
// plugin the page is named after, and that plugin has documentation data.

import { PLUGIN_SECTIONS } from "../../lib/content-core.mjs";

/** The component that renders each generated section (see components/mdx/PluginSections.tsx). */
export const GENERATED = Object.freeze({
  capabilities: "PluginCapabilities",
  versions: "PluginVersions",
  options: "PluginOptions",
  "native-options": "PluginNativeOptions",
  "type-mapping": "PluginTypeMapping",
  changelog: "PluginChangelog",
});

const TAG = /^<(Plugin[A-Za-z]+)\s+plugin="([a-z0-9-]+)"(?:\s+component="([a-z]+)")?\s*\/>$/;

/** The lines of each level 2 section of a page body, outside code fences. */
export function sectionBodies(body) {
  const sections = new Map();
  let current = null;
  let inFence = false;
  for (const line of body.split("\n")) {
    if (/^\s*(`{3,}|~{3,})/.test(line)) inFence = !inFence;
    const heading = !inFence && line.match(/^##\s+(.+)$/);
    if (heading) {
      current = heading[1].trim();
      sections.set(current, []);
    } else if (current !== null && line.trim() !== "") {
      sections.get(current).push(line.trim());
    }
  }
  return sections;
}

/**
 * @param pages the documentation pages
 * @param plugins the collected plugin data, or null when it was not generated (only the form is checked)
 */
export function checkPlugins(pages, plugins) {
  const errors = [];
  const byId = new Map((plugins ?? []).map((p) => [p.id, p]));
  for (const page of pages) {
    if ((page.frontmatter.template ?? "guide") !== "plugin") continue;
    const where = `${page.lang}/${page.id}`;
    const expectedId = page.id.split("/").at(-1);
    const bodies = sectionBodies(page.body);
    for (const section of PLUGIN_SECTIONS.filter((s) => s.generated)) {
      const lines = bodies.get(section[page.lang]);
      if (!lines) continue;
      const tag = lines.length === 1 ? lines[0].match(TAG) : null;
      if (!tag || tag[1] !== GENERATED[section.key]) {
        errors.push(
          `${where}: section '${section[page.lang]}' is generated; it must contain only <${GENERATED[section.key]} plugin="${expectedId}" />`,
        );
        continue;
      }
      if (tag[2] !== expectedId) {
        errors.push(`${where}: <${tag[1]}> names plugin '${tag[2]}' but the page is for '${expectedId}'`);
        continue;
      }
      if (plugins === null) continue;
      const plugin = byId.get(tag[2]);
      if (!plugin) {
        errors.push(
          `${where}: plugin '${tag[2]}' has no documentation data (src/generated/plugin-docs/${tag[2]}.json)`,
        );
      } else if (tag[3] && !plugin.components.some((c) => c.type === tag[3])) {
        errors.push(`${where}: plugin '${tag[2]}' has no ${tag[3]} component`);
      }
    }
  }
  return errors;
}
