/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { createCssVariablesTheme, createHighlighter, type Highlighter } from "shiki";

const theme = createCssVariablesTheme({ name: "pipeforge", variablePrefix: "--shiki-", fontStyle: true });

export const LANGUAGES = [
  "bash",
  "shell",
  "yaml",
  "json",
  "java",
  "sql",
  "typescript",
  "tsx",
  "xml",
  "properties",
  "text",
];

let highlighter: Promise<Highlighter> | undefined;

function getHighlighter(): Promise<Highlighter> {
  highlighter ??= createHighlighter({ themes: [theme], langs: LANGUAGES.filter((l) => l !== "text") });
  return highlighter;
}

/** Highlights code into Shiki tokens; unknown languages fall back to plain text. */
export async function highlightLines(code: string, lang: string) {
  const shiki = await getHighlighter();
  const known = shiki.getLoadedLanguages().includes(lang);
  const { tokens } = shiki.codeToTokens(code, { lang: known ? (lang as never) : "text", theme: "pipeforge" });
  return tokens.map((line) =>
    line.map((token) => ({ content: token.content, color: token.color, fontStyle: token.fontStyle })),
  );
}

/** Parses a code fence meta string: title="file.yaml", {1,3-4} and showLineNumbers. */
export function parseMeta(meta: string | undefined) {
  const result = { title: undefined as string | undefined, highlight: new Set<number>(), lineNumbers: false };
  if (!meta) return result;
  const title = meta.match(/title="([^"]+)"/);
  if (title) result.title = title[1];
  const ranges = meta.match(/\{([\d,\s-]+)\}/);
  if (ranges?.[1]) {
    for (const part of ranges[1].split(",")) {
      const [start, end] = part.trim().split("-").map(Number);
      if (start === undefined || Number.isNaN(start)) continue;
      for (let line = start; line <= (end ?? start); line += 1) result.highlight.add(line);
    }
  }
  result.lineNumbers = /\bshowLineNumbers\b/.test(meta);
  return result;
}
