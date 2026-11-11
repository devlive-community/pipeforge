/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Content model shared by the site (app/) and the build scripts (scripts/). Plain JavaScript
// so that Node can run the scripts without a build step.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, posix, relative, sep } from "node:path";
import matter from "gray-matter";

export const LANGUAGES = Object.freeze(["en-US", "zh-CN"]);
export const DEFAULT_LANGUAGE = "en-US";
export const VERSIONS = Object.freeze(["next"]);
export const CURRENT_VERSION = "next";

/** Top-level content sections, in navigation order. */
export const SECTIONS = Object.freeze([
  "getting-started",
  "concepts",
  "guides",
  "reference",
  "plugins",
  "operations",
  "development",
]);

/**
 * Sections of the feature documentation template, in order, with the heading each language
 * uses. A feature page must contain every section unless its frontmatter `omit` explains why.
 */
export const FEATURE_SECTIONS = Object.freeze([
  { key: "overview", "en-US": "Overview", "zh-CN": "概述" },
  { key: "principle", "en-US": "How it works", "zh-CN": "原理" },
  { key: "quickstart", "en-US": "Quick start", "zh-CN": "快速上手" },
  { key: "configuration", "en-US": "Configuration", "zh-CN": "配置" },
  { key: "examples", "en-US": "Examples", "zh-CN": "示例" },
  { key: "behavior", "en-US": "Behavior", "zh-CN": "行为与语义" },
  { key: "observability", "en-US": "Observability", "zh-CN": "可观测性" },
  { key: "performance", "en-US": "Performance and tuning", "zh-CN": "性能与调优" },
  { key: "troubleshooting", "en-US": "Troubleshooting", "zh-CN": "排障" },
  { key: "limitations", "en-US": "Limitations", "zh-CN": "限制" },
  { key: "version", "en-US": "Version history", "zh-CN": "版本" },
]);

/**
 * Sections of the plugin page template (plugins/<type>/<id>.mdx). Sections marked
 * generated render data exported from the plugin and must not be written by hand.
 */
export const PLUGIN_SECTIONS = Object.freeze([
  { key: "introduction", "en-US": "Introduction", "zh-CN": "简介" },
  { key: "capabilities", "en-US": "Capabilities", "zh-CN": "能力", generated: true },
  { key: "versions", "en-US": "Verified versions", "zh-CN": "已验证版本", generated: true },
  { key: "quickstart", "en-US": "Quick start", "zh-CN": "快速开始" },
  { key: "prerequisites", "en-US": "Prerequisites", "zh-CN": "前置条件" },
  { key: "datasource", "en-US": "Data source configuration", "zh-CN": "数据源配置" },
  { key: "options", "en-US": "Node options", "zh-CN": "节点选项", generated: true },
  { key: "native-options", "en-US": "Native options", "zh-CN": "原生配置项目录", generated: true },
  { key: "type-mapping", "en-US": "Type mapping", "zh-CN": "类型映射", generated: true },
  { key: "parallelism", "en-US": "Splits and parallelism", "zh-CN": "分片与并行" },
  { key: "consistency", "en-US": "Consistency", "zh-CN": "一致性语义" },
  { key: "examples", "en-US": "Examples", "zh-CN": "示例" },
  { key: "metrics", "en-US": "Metrics", "zh-CN": "指标" },
  { key: "performance", "en-US": "Performance and tuning", "zh-CN": "性能与调优" },
  { key: "troubleshooting", "en-US": "Errors and troubleshooting", "zh-CN": "错误码与排障" },
  { key: "limitations", "en-US": "Known limitations", "zh-CN": "已知限制" },
  { key: "changelog", "en-US": "Changelog", "zh-CN": "变更记录", generated: true },
]);

/** Page templates: "guide" is free-form, the others prescribe their level 2 sections. */
export const TEMPLATES = Object.freeze(["guide", "feature", "plugin"]);

/** Sections prescribed by a template, or null for free-form pages. */
export function templateSections(template) {
  if (template === "feature") return FEATURE_SECTIONS;
  if (template === "plugin") return PLUGIN_SECTIONS;
  return null;
}

/**
 * Turns heading text into an anchor id. Letters and digits of every script are kept, so
 * Chinese headings produce readable anchors as well.
 */
export function slugify(text) {
  return text
    .toLowerCase()
    .replace(/`([^`]*)`/g, "$1")
    .replace(/[^\p{L}\p{N}\s-]/gu, "")
    .trim()
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-");
}

/** Removes inline Markdown so that heading text matches what the reader sees. */
export function plainText(markdown) {
  return markdown
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/[*_~]/g, "")
    .replace(/`([^`]*)`/g, "$1")
    .trim();
}

/** Returns the lines of a Markdown body that are outside fenced code blocks. */
export function proseLines(body) {
  const lines = [];
  let fence = null;
  for (const line of body.split("\n")) {
    const match = line.match(/^\s*(`{3,}|~{3,})/);
    if (match) {
      if (fence === null) fence = match[1][0];
      else if (match[1][0] === fence) fence = null;
      continue;
    }
    if (fence === null) lines.push(line);
  }
  return lines;
}

/**
 * Extracts the level 2 and 3 headings with unique anchor ids, in document order. The site
 * assigns the same ids when it renders the page.
 */
export function extractHeadings(body) {
  const seen = new Map();
  const headings = [];
  for (const line of proseLines(body)) {
    const match = line.match(/^(#{2,3})\s+(.+?)\s*#*\s*$/);
    if (!match) continue;
    const text = plainText(match[2]);
    headings.push({ depth: match[1].length, text, id: uniqueSlug(text, seen) });
  }
  return headings;
}

/** Returns a slug that is unique within `seen`, appending -1, -2, ... for repeats. */
export function uniqueSlug(text, seen) {
  const base = slugify(text) || "section";
  const count = seen.get(base) ?? 0;
  seen.set(base, count + 1);
  return count === 0 ? base : `${base}-${count}`;
}

/** Counts fenced code blocks in a Markdown body. */
export function countCodeBlocks(body) {
  let count = 0;
  let fence = null;
  for (const line of body.split("\n")) {
    const match = line.match(/^\s*(`{3,}|~{3,})/);
    if (!match) continue;
    if (fence === null) {
      fence = match[1][0];
      count += 1;
    } else if (match[1][0] === fence) {
      fence = null;
    }
  }
  return count;
}

/** Collects Markdown link targets (outside code), with their line numbers. */
export function extractLinks(body) {
  const links = [];
  let lineNo = 0;
  let fence = null;
  for (const line of body.split("\n")) {
    lineNo += 1;
    const fenceMatch = line.match(/^\s*(`{3,}|~{3,})/);
    if (fenceMatch) {
      if (fence === null) fence = fenceMatch[1][0];
      else if (fenceMatch[1][0] === fence) fence = null;
      continue;
    }
    if (fence !== null) continue;
    const withoutCode = line.replace(/`[^`]*`/g, "");
    for (const match of withoutCode.matchAll(/(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) {
      links.push({ href: match[1], line: lineNo });
    }
    for (const match of withoutCode.matchAll(/href="([^"]+)"/g)) {
      links.push({ href: match[1], line: lineNo });
    }
  }
  return links;
}

/**
 * Resolves a link written in a page to a target page id and anchor. Content links are
 * written relative to the page (./docs-guide, ../concepts/plugins#loading) and never
 * contain the language or version, so the same text works in every language and version.
 *
 * Returns {kind: "external"} for absolute URLs, {kind: "page", id, anchor} for content
 * links, and {kind: "invalid", reason} for links that cannot be resolved.
 */
export function resolveLink(fromId, href) {
  if (/^[a-z][a-z0-9+.-]*:/i.test(href)) return { kind: "external", href };
  if (href.startsWith("//")) return { kind: "external", href };
  if (href.startsWith("/")) {
    return { kind: "invalid", reason: "site-absolute links break across languages; use a relative link" };
  }
  const [pathPart, anchor = ""] = href.split("#");
  if (pathPart === "") return { kind: "page", id: fromId, anchor };
  const cleaned = pathPart.replace(/\.mdx?$/, "").replace(/\/$/, "");
  const id = posix.normalize(posix.join(posix.dirname(fromId), cleaned));
  if (id.startsWith("..")) return { kind: "invalid", reason: "link leaves the content root" };
  return { kind: "page", id: id === "." ? "index" : id, anchor };
}

/** URL of a page, relative to the site base path. */
export function pageUrl(lang, version, id) {
  return `/${lang}/${version}/${id}/`;
}

function walk(dir) {
  const files = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) files.push(...walk(full));
    else if (name.endsWith(".mdx")) files.push(full);
  }
  return files;
}

/** Parses one MDX file into a page record. */
export function parsePage(lang, id, source, file = `${lang}/${id}.mdx`) {
  const { data, content } = matter(source);
  return { lang, id, file, frontmatter: data, body: content, headings: extractHeadings(content) };
}

/** Loads every page of every language from a content directory. */
export function loadPages(contentDir) {
  const pages = [];
  for (const lang of LANGUAGES) {
    const root = join(contentDir, lang);
    let files = [];
    try {
      files = walk(root);
    } catch {
      continue;
    }
    for (const file of files) {
      const id = relative(root, file)
        .split(sep)
        .join("/")
        .replace(/\.mdx$/, "");
      pages.push(parsePage(lang, id, readFileSync(file, "utf8"), relative(contentDir, file)));
    }
  }
  return pages;
}

/** Orders pages by section, then by frontmatter `order`, then by id. */
export function sortPages(pages) {
  const sectionIndex = (id) => {
    const index = SECTIONS.indexOf(id.split("/")[0]);
    return index === -1 ? SECTIONS.length : index;
  };
  return [...pages].sort(
    (a, b) =>
      sectionIndex(a.id) - sectionIndex(b.id) ||
      (a.frontmatter.order ?? 1000) - (b.frontmatter.order ?? 1000) ||
      a.id.localeCompare(b.id),
  );
}
