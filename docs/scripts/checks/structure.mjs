/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Content completeness: frontmatter, the sections prescribed by the feature and plugin
// templates, no empty sections and no TODO markers in hand-written content.

import { SECTIONS, TEMPLATES, proseLines, templateSections } from "../../lib/content-core.mjs";

export function checkStructure(pages) {
  const errors = [];
  for (const page of pages) {
    const where = `${page.file}`;
    const fm = page.frontmatter;
    if (typeof fm.title !== "string" || fm.title.trim() === "") errors.push(`${where}: frontmatter needs a title`);
    if (typeof fm.description !== "string" || fm.description.trim() === "") {
      errors.push(`${where}: frontmatter needs a description`);
    }
    if (fm.order !== undefined && typeof fm.order !== "number") errors.push(`${where}: order must be a number`);
    const template = fm.template ?? "guide";
    if (!TEMPLATES.includes(template)) errors.push(`${where}: unknown template '${template}'`);
    if (!SECTIONS.includes(page.id.split("/")[0])) {
      errors.push(`${where}: '${page.id.split("/")[0]}' is not a content section (${SECTIONS.join(", ")})`);
    }
    if (page.body.trim() === "") errors.push(`${where}: page has no content`);
    const prose = proseLines(page.body)
      .join("\n")
      .replace(/`[^`]*`/g, "");
    if (/\bTODO\b/.test(prose)) errors.push(`${where}: remove the TODO marker`);
    if (/^#\s/m.test(proseLines(page.body).join("\n"))) {
      errors.push(`${where}: do not use a level 1 heading; the title comes from the frontmatter`);
    }
    const sections = templateSections(template);
    if (sections) errors.push(...checkTemplateSections(page, where, sections, template));
    if (template === "plugin" && !page.id.startsWith("plugins/")) {
      errors.push(`${where}: plugin pages live under plugins/<type>/`);
    }
  }
  return errors;
}

function checkTemplateSections(page, where, SECTIONS_OF_TEMPLATE, template) {
  const errors = [];
  const omit = page.frontmatter.omit ?? {};
  if (typeof omit !== "object" || Array.isArray(omit)) return [`${where}: omit must map section keys to reasons`];
  const known = new Set(SECTIONS_OF_TEMPLATE.map((s) => s.key));
  for (const [key, reason] of Object.entries(omit)) {
    if (!known.has(key)) errors.push(`${where}: omit names unknown section '${key}'`);
    if (typeof reason !== "string" || reason.trim() === "") errors.push(`${where}: omitting '${key}' needs a reason`);
  }

  const expected = SECTIONS_OF_TEMPLATE.filter((s) => !(s.key in omit)).map((s) => s[page.lang]);
  const actual = page.headings.filter((h) => h.depth === 2).map((h) => h.text);
  for (const section of SECTIONS_OF_TEMPLATE) {
    const heading = section[page.lang];
    const present = actual.includes(heading);
    if (section.key in omit && present) errors.push(`${where}: section '${heading}' is both present and omitted`);
    if (!(section.key in omit) && !present) errors.push(`${where}: missing section '${heading}'`);
  }
  const extra = actual.filter((h) => !SECTIONS_OF_TEMPLATE.some((s) => s[page.lang] === h));
  for (const heading of extra) errors.push(`${where}: '${heading}' is not a section of the ${template} template`);
  const ordered = actual.filter((h) => expected.includes(h));
  if (ordered.join("|") !== expected.filter((h) => actual.includes(h)).join("|")) {
    errors.push(`${where}: sections are not in template order (${expected.join(", ")})`);
  }
  errors.push(...emptySections(page, where));
  return errors;
}

function emptySections(page, where) {
  const errors = [];
  let current = null;
  let hasContent = false;
  const flush = () => {
    if (current !== null && !hasContent) errors.push(`${where}: section '${current}' is empty`);
  };
  let inFence = false;
  for (const line of page.body.split("\n")) {
    if (/^\s*(`{3,}|~{3,})/.test(line)) {
      inFence = !inFence;
      hasContent = true;
      continue;
    }
    const heading = !inFence && line.match(/^##\s+(.+)$/);
    if (heading) {
      flush();
      current = heading[1].trim();
      hasContent = false;
    } else if (line.trim() !== "") {
      hasContent = true;
    }
  }
  flush();
  return errors;
}
