/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Every page exists in every language with the same structure: template, order, omitted
// sections, heading levels and number of code blocks.

import { LANGUAGES, countCodeBlocks } from "../../lib/content-core.mjs";

export function checkI18n(pages) {
  const errors = [];
  const byLang = new Map(LANGUAGES.map((lang) => [lang, new Map()]));
  for (const page of pages) byLang.get(page.lang)?.set(page.id, page);

  const [reference, ...others] = LANGUAGES;
  const ids = new Set(pages.map((p) => p.id));
  for (const id of [...ids].sort()) {
    for (const lang of LANGUAGES) {
      if (!byLang.get(lang).has(id)) errors.push(`${lang}/${id}.mdx: missing translation`);
    }
    const base = byLang.get(reference).get(id);
    if (!base) continue;
    for (const lang of others) {
      const other = byLang.get(lang).get(id);
      if (!other) continue;
      const where = `${id}: ${reference} and ${lang}`;
      const fmA = base.frontmatter;
      const fmB = other.frontmatter;
      if ((fmA.template ?? "guide") !== (fmB.template ?? "guide")) errors.push(`${where} use different templates`);
      if (fmA.order !== fmB.order) errors.push(`${where} have different order values`);
      const omitA = Object.keys(fmA.omit ?? {})
        .sort()
        .join(",");
      const omitB = Object.keys(fmB.omit ?? {})
        .sort()
        .join(",");
      if (omitA !== omitB) errors.push(`${where} omit different sections (${omitA || "none"} / ${omitB || "none"})`);
      const levelsA = base.headings.map((h) => h.depth).join("");
      const levelsB = other.headings.map((h) => h.depth).join("");
      if (levelsA !== levelsB) {
        errors.push(
          `${where} have different heading structures (${base.headings.length} / ${other.headings.length} headings)`,
        );
      }
      const codeA = countCodeBlocks(base.body);
      const codeB = countCodeBlocks(other.body);
      if (codeA !== codeB) errors.push(`${where} have different numbers of code blocks (${codeA} / ${codeB})`);
    }
  }
  return errors;
}
