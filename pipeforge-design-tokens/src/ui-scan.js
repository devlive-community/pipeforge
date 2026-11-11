/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// UI rule scan shared by the Web UI (pnpm lint:ui) and the documentation site: only semantic
// tokens and the own component library. Reports raw palette classes, arbitrary colors, dark:
// variants, inline colors, native interactive controls and CodeMirror imports outside the
// component library, title tooltips, alert/confirm/prompt and hex colors in CSS.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const PALETTE =
  "slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose|black|white";
const UTILITY = "bg|text|border|ring|fill|stroke|from|to|via|outline|divide|placeholder|caret|accent|decoration|shadow";

export const RULES = [
  {
    id: "raw-palette",
    message: "use a semantic token instead of a raw palette color",
    pattern: new RegExp(`(?<![\\w-])(?:[a-z-]+:)*(?:${UTILITY})-(?:${PALETTE})(?:-\\d{2,3})?(?:\\/\\d+)?(?![\\w-])`),
  },
  {
    id: "arbitrary-color",
    message: "arbitrary color values bypass the tokens",
    pattern: /(?<![\w-])(?:[a-z-]+:)*(?:bg|text|border|ring|fill|stroke|shadow|outline)-\[(?:#|rgb|hsl|oklch|color)/,
  },
  {
    id: "dark-variant",
    message: "dark: variants are not allowed; tokens switch with the theme",
    pattern: /(?<![\w-])dark:[a-z]/,
  },
  {
    id: "inline-color",
    message: "inline color styles bypass the tokens",
    pattern: /style=\{\{[^}]*\b(?:color|background(?:Color)?|borderColor|fill|stroke)\s*:/,
  },
  {
    id: "native-control",
    message: "use the components of the component library instead of native controls",
    pattern: /<(?:select|textarea|dialog|button|input)\b/,
    componentsOnly: true,
  },
  {
    id: "title-tooltip",
    message: "title tooltips are not allowed; use a tooltip component",
    pattern: /<[a-z][a-z0-9-]*\s[^>]*\btitle=["{]/,
    jsxOnly: true,
  },
  {
    id: "browser-dialog",
    message: "alert/confirm/prompt are not allowed",
    pattern: /(?<![\w.])(?:window\.)?(?:alert|confirm|prompt)\(/,
  },
  {
    id: "codemirror-import",
    message: "CodeMirror is only used through the CodeEditor component",
    pattern: /from\s+["']@codemirror\//,
    componentsOnly: true,
  },
  {
    id: "css-hex-color",
    message: "colors in CSS must come from the tokens",
    pattern: /#[0-9a-fA-F]{3,8}\b/,
    cssOnly: true,
  },
];

const SCANNED = [".tsx", ".ts", ".jsx", ".js", ".mjs", ".css"];

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    if (name === "node_modules" || name.startsWith(".")) continue;
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else if (SCANNED.some((ext) => name.endsWith(ext))) out.push(full);
  }
  return out;
}

/**
 * Scans one file's text.
 *
 * @param path the path relative to the scanned root, with forward slashes
 * @param componentDirs directories (with a trailing slash) of the component library, the only
 *     place where native controls and CodeMirror may be used
 */
export function scanSource(path, text, componentDirs = ["components/ui/"]) {
  const violations = [];
  const isCss = path.endsWith(".css");
  const isJsx = path.endsWith(".tsx") || path.endsWith(".jsx");
  const lines = text.split("\n");
  lines.forEach((line, i) => {
    if (/ui-scan-ignore/.test(line) || /^\s*(\/\/|\*|\/\*)/.test(line)) return;
    for (const rule of RULES) {
      if (rule.cssOnly && !isCss) continue;
      if (rule.jsxOnly && !isJsx) continue;
      if (rule.componentsOnly && componentDirs.some((dir) => path.startsWith(dir))) continue;
      if (rule.pattern.test(line)) violations.push({ path, line: i + 1, rule: rule.id, message: rule.message });
    }
  });
  return violations;
}

/** Scans the sources in `dirs` under `root`; returns "path:line: rule: message" lines. */
export function scanUi(root, dirs, componentDirs = ["components/ui/"]) {
  const violations = [];
  for (const dir of dirs) {
    let files = [];
    try {
      files = walk(join(root, dir));
    } catch {
      continue;
    }
    for (const file of files) {
      const path = relative(root, file).split(sep).join("/");
      violations.push(...scanSource(path, readFileSync(file, "utf8"), componentDirs));
    }
  }
  return violations.map((v) => `${v.path}:${v.line}: ${v.rule}: ${v.message}`);
}
