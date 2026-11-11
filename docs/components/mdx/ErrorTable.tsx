/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { useMemo, useState } from "react";
import type { Lang } from "@/lib/i18n";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { Icon } from "../ui/Icon";
import { TextInput } from "../ui/TextInput";

export interface ErrorRow {
  code: string;
  name: string;
  category: string;
  kind: string;
  deprecated: boolean;
  message: string;
  hint: string;
}

const LABELS = {
  "en-US": {
    filter: "Filter error codes",
    all: "All",
    kind: "Kind",
    deprecated: "Deprecated",
    none: "No matching error codes",
    count: "codes",
  },
  "zh-CN": {
    filter: "筛选错误码",
    all: "全部",
    kind: "分类",
    deprecated: "已废弃",
    none: "没有匹配的错误码",
    count: "个错误码",
  },
} as const;

const KIND_STYLE: Record<string, string> = {
  FATAL: "bg-danger-soft text-fg",
  TRANSIENT: "bg-warning-soft text-fg",
  DATA: "bg-info-soft text-fg",
};

/** Client-side filtering of the error reference by text and category. */
export function ErrorTable({ entries, lang }: { entries: ErrorRow[]; lang: Lang }) {
  const labels = LABELS[lang];
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<string | null>(null);
  const categories = useMemo(() => [...new Set(entries.map((e) => e.category))], [entries]);
  const needle = query.trim().toLowerCase();
  const visible = entries.filter(
    (e) =>
      (category === null || e.category === category) &&
      (needle === "" || [e.code, e.name, e.message, e.hint].some((text) => text.toLowerCase().includes(needle))),
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
        {[null, ...categories].map((c) => (
          <Button
            key={c ?? "all"}
            size="sm"
            variant={category === c ? "primary" : "secondary"}
            aria-pressed={category === c}
            onClick={() => setCategory(c)}
          >
            {c ?? labels.all}
          </Button>
        ))}
      </div>
      <p className="text-sm text-fg-muted" aria-live="polite">
        {visible.length} {labels.count}
      </p>
      <ul className="space-y-3">
        {visible.map((e) => (
          <li
            key={e.code}
            id={e.code.toLowerCase()}
            className="scroll-mt-24 rounded-card border border-border p-4 transition-colors duration-fast target:border-primary"
          >
            <div className="flex flex-wrap items-center gap-2">
              <a href={`#${e.code.toLowerCase()}`} className="font-mono text-sm font-semibold text-primary">
                {e.code}
              </a>
              <code className="font-mono text-xs text-fg-muted">{e.name}</code>
              <span className={cx("rounded-control px-1.5 py-0.5 text-xs font-medium", KIND_STYLE[e.kind])}>
                {labels.kind}: {e.kind}
              </span>
              {e.deprecated ? (
                <span className="rounded-control bg-surface-3 px-1.5 py-0.5 text-xs text-fg-muted">
                  {labels.deprecated}
                </span>
              ) : null}
            </div>
            <p className="mt-2 text-fg">{e.message}</p>
            <p className="mt-1 text-sm text-fg-muted">{e.hint}</p>
          </li>
        ))}
      </ul>
      {visible.length === 0 ? <p className="text-sm text-fg-muted">{labels.none}</p> : null}
    </div>
  );
}
