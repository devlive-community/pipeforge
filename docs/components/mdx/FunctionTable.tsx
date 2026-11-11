/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { useState } from "react";
import type { Lang } from "@/lib/i18n";
import { Button } from "../ui/Button";
import { Icon } from "../ui/Icon";
import { TextInput } from "../ui/TextInput";

export interface FunctionRow {
  name: string;
  category: string;
  syntax: string | null;
  deterministic: boolean;
  signatures: string[];
  description: string;
  nulls: string | null;
  examples: { expression: string; result: string }[];
}

const CATEGORIES = ["comparison", "numeric", "string", "datetime", "conversion"] as const;

const LABELS = {
  "en-US": {
    filter: "Filter functions",
    all: "All",
    categories: {
      comparison: "Comparison",
      numeric: "Numeric",
      string: "String",
      datetime: "Date and time",
      conversion: "Conversion",
    },
    count: "functions",
    none: "No matching functions",
    nulls: "NULL",
    nullDefault: "NULL when any argument is NULL.",
    nondeterministic: "Not deterministic",
    examples: "Examples",
  },
  "zh-CN": {
    filter: "筛选函数",
    all: "全部",
    categories: {
      comparison: "比较与条件",
      numeric: "数值",
      string: "字符串",
      datetime: "日期时间",
      conversion: "类型转换",
    },
    count: "个函数",
    none: "没有匹配的函数",
    nulls: "NULL",
    nullDefault: "任一参数为 NULL 时结果为 NULL。",
    nondeterministic: "不确定",
    examples: "示例",
  },
} as const;

/** Client-side filtering of the function reference by text and category. */
export function FunctionTable({ entries, lang }: { entries: FunctionRow[]; lang: Lang }) {
  const labels = LABELS[lang];
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<string | null>(null);
  const needle = query.trim().toLowerCase();
  const visible = entries.filter(
    (e) =>
      (category === null || e.category === category) &&
      (needle === "" || [e.name, e.description, ...e.signatures].some((text) => text.toLowerCase().includes(needle))),
  );

  return (
    <div className="not-prose space-y-4">
      <div className="flex items-center gap-2 rounded-control border border-border px-3 focus-within:border-primary">
        <Icon name="search" size={15} className="text-fg-subtle" />
        <TextInput
          label={labels.filter}
          placeholder={labels.filter}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      <div role="group" aria-label={labels.filter} className="flex flex-wrap gap-1.5">
        {[null, ...CATEGORIES].map((c) => (
          <Button
            key={c ?? "all"}
            size="sm"
            variant={category === c ? "primary" : "secondary"}
            aria-pressed={category === c}
            onClick={() => setCategory(c)}
          >
            {c === null ? labels.all : labels.categories[c]}
          </Button>
        ))}
      </div>
      <p className="text-sm text-fg-muted" aria-live="polite">
        {visible.length} {labels.count}
      </p>
      <ul className="space-y-3">
        {visible.map((f) => (
          <li
            key={f.name}
            id={f.name.toLowerCase()}
            className="scroll-mt-24 rounded-card border border-border p-4 target:border-primary"
          >
            <div className="flex flex-wrap items-center gap-2">
              <a href={`#${f.name.toLowerCase()}`} className="font-mono text-sm font-semibold text-primary">
                {f.name}
              </a>
              <span className="rounded-control bg-surface-3 px-1.5 py-0.5 text-xs text-fg-muted">
                {labels.categories[f.category as keyof typeof labels.categories] ?? f.category}
              </span>
              {f.deterministic ? null : (
                <span className="rounded-control bg-warning-soft px-1.5 py-0.5 text-xs text-fg">
                  {labels.nondeterministic}
                </span>
              )}
            </div>
            <p className="mt-2 text-fg">{f.description}</p>
            <div className="mt-2 space-y-0.5 font-mono text-xs text-fg-muted">
              {f.syntax ? <p>{f.syntax}</p> : null}
              {f.signatures.map((s) => (
                <p key={s}>{s}</p>
              ))}
            </div>
            <p className="mt-2 text-sm text-fg-muted">
              <span className="font-medium text-fg">{labels.nulls}: </span>
              {f.nulls ?? labels.nullDefault}
            </p>
            <table className="mt-3 w-full text-sm">
              <caption className="sr-only">{labels.examples}</caption>
              <tbody>
                {f.examples.map((example) => (
                  <tr key={example.expression} className="border-t border-border">
                    <td className="py-1.5 pr-3 font-mono text-xs text-fg">{example.expression}</td>
                    <td className="py-1.5 font-mono text-xs text-fg-muted">→ {example.result}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </li>
        ))}
      </ul>
      {visible.length === 0 ? <p className="text-sm text-fg-muted">{labels.none}</p> : null}
    </div>
  );
}
