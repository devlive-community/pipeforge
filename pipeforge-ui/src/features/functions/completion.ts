/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { EditorSuggestion } from "@/ui";

/** Keywords of the expression language that start or join expressions. */
export const KEYWORDS = [
  "CASE",
  "WHEN",
  "THEN",
  "ELSE",
  "END",
  "AND",
  "OR",
  "NOT",
  "IS",
  "NULL",
  "IN",
  "BETWEEN",
  "LIKE",
  "CAST",
  "TRY_CAST",
  "AS",
  "TRUE",
  "FALSE",
];

export interface CompletableFunction {
  name: string;
  signatures?: string[];
  description?: string;
}

export interface CompletableColumn {
  name: string;
  type: string;
}

/**
 * Suggestions for an expression at `position`: columns, functions (inserted with an opening
 * parenthesis, with their first signature and description) and keywords. Nothing is offered inside
 * a string literal, nor without a typed word unless asked explicitly.
 */
export function expressionCompletion(
  text: string,
  position: number,
  explicit: boolean,
  functions: CompletableFunction[],
  columns: CompletableColumn[],
): { from: number; options: EditorSuggestion[] } | null {
  const before = text.slice(0, position);
  if ((before.match(/'/g) ?? []).length % 2 === 1) return null;
  const typed = /[A-Za-z_][A-Za-z0-9_]*$/.exec(before)?.[0] ?? "";
  if (!typed && !explicit) return null;
  const options: EditorSuggestion[] = [
    ...columns
      .filter((c) => c.name)
      .map((c): EditorSuggestion => ({ label: c.name, type: "variable", detail: c.type })),
    ...functions.map(
      (f): EditorSuggestion => ({
        label: f.name,
        type: "property",
        detail: f.signatures?.[0],
        info: f.description,
        apply: `${f.name}(`,
      }),
    ),
    ...KEYWORDS.map((k): EditorSuggestion => ({ label: k, type: "keyword" })),
  ];
  return { from: position - typed.length, options };
}
