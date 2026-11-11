/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Internal links and anchors must resolve to an existing page and heading in the same
// language. External links are checked weekly by a separate job and only produce warnings.

import { extractLinks, resolveLink } from "../../lib/content-core.mjs";

export function checkLinks(pages) {
  const errors = [];
  const index = new Map(pages.map((p) => [`${p.lang}/${p.id}`, p]));
  for (const page of pages) {
    for (const link of extractLinks(page.body)) {
      const where = `${page.file}:${link.line}`;
      const target = resolveLink(page.id, link.href);
      if (target.kind === "external") continue;
      if (target.kind === "invalid") {
        errors.push(`${where}: ${link.href}: ${target.reason}`);
        continue;
      }
      const targetPage = index.get(`${page.lang}/${target.id}`);
      if (!targetPage) {
        errors.push(`${where}: ${link.href}: page '${target.id}' does not exist`);
        continue;
      }
      if (target.anchor && !targetPage.headings.some((h) => h.id === target.anchor)) {
        errors.push(`${where}: ${link.href}: '${target.id}' has no heading '#${target.anchor}'`);
      }
    }
  }
  return errors;
}
