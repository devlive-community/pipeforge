/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Builds the client-side search documents for one language: title, headings and plain text
// of every page, without Markdown, code blocks or MDX tags.

import { CURRENT_VERSION, pageUrl, proseLines, sortPages } from "../lib/content-core.mjs";

export function plainBody(body) {
  return proseLines(body)
    .join("\n")
    .replace(/^import .*$/gm, "")
    .replace(/<[^>]+>/g, " ")
    .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/[#>*_`|~-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export function buildSearchDocuments(pages, lang) {
  return sortPages(pages.filter((p) => p.lang === lang)).map((page, index) => ({
    id: index,
    title: String(page.frontmatter.title ?? page.id),
    url: pageUrl(lang, CURRENT_VERSION, page.id),
    section: page.id.split("/")[0],
    headings: page.headings.map((h) => h.text).join(" "),
    text: plainBody(page.body).slice(0, 20000),
  }));
}
