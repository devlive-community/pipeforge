/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { components } from "@pipeforge/client";
import { useMemo, useState } from "react";
import { useErrorCodes } from "@/api/queries";
import { useScrollToHash } from "@/app/useScrollToHash";
import { useT } from "@/i18n";
import { Badge, Card, Empty, Input, PageHeader, SegmentedControl, Skeleton, type BadgeTone } from "@/ui";

type ErrorCodeInfo = components["schemas"]["ErrorCodeInfo"];
type Kind = "FATAL" | "TRANSIENT" | "DATA";
const KIND_TONES: Record<Kind, BadgeTone> = { FATAL: "danger", TRANSIENT: "warning", DATA: "info" };

/** A message template with its {placeholders} set apart, as the server fills them in. */
function Template({ text }: { text: string }) {
  return (
    <>
      {text.split(/(\{[a-zA-Z][a-zA-Z0-9_]*\})/).map((part, i) =>
        i % 2 === 1 ? (
          <code key={i} className="rounded-sm bg-surface-3 px-1 font-mono text-xs text-fg">
            {part}
          </code>
        ) : (
          part
        ),
      )}
    </>
  );
}

function ErrorCodeEntry({ entry }: { entry: ErrorCodeInfo }) {
  const t = useT();
  const code = entry.code ?? "";
  const kind = entry.kind as Kind | undefined;
  return (
    <article
      id={code.toLowerCase()}
      tabIndex={-1}
      className="scroll-mt-4 rounded-card border border-border p-4 outline-none target:border-primary"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="font-mono text-sm font-semibold text-fg">{code}</h2>
        <span className="font-mono text-xs text-fg-muted">{entry.name}</span>
        {kind && kind in KIND_TONES ? <Badge tone={KIND_TONES[kind]}>{t(`errors.kinds.${kind}`)}</Badge> : null}
        {entry.deprecated ? <Badge tone="warning">{t("errors.deprecated")}</Badge> : null}
      </div>
      <p className="mt-2 text-sm text-fg">
        <Template text={entry.message ?? ""} />
      </p>
      {entry.hint ? (
        <p className="mt-1 text-sm text-fg-muted">
          <span className="font-medium text-fg">{t("errors.hint")}: </span>
          <Template text={entry.hint} />
        </p>
      ) : null}
      {entry.params && entry.params.length > 0 ? (
        <p className="mt-1 text-sm text-fg-muted">
          <span className="font-medium text-fg">{t("errors.params")}: </span>
          {entry.params.map((p, i) => (
            <span key={p}>
              {i > 0 ? ", " : null}
              <code className="font-mono text-xs text-fg">{p}</code>
            </span>
          ))}
        </p>
      ) : null}
    </article>
  );
}

/** Every registered error code, with search and categories; #pf-conf-0006 opens at one code. */
export function ErrorCodesPage() {
  const t = useT();
  const codes = useErrorCodes();
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("all");
  useScrollToHash(Boolean(codes.data));

  const all = useMemo(() => codes.data ?? [], [codes.data]);
  const categories = useMemo(() => [...new Set(all.map((c) => c.category ?? ""))].filter(Boolean), [all]);
  const needle = query.trim().toLowerCase();
  const visible = all.filter(
    (c) =>
      (category === "all" || c.category === category) &&
      (needle === "" ||
        [c.code ?? "", c.name ?? "", c.message ?? "", c.hint ?? ""].some((text) =>
          text.toLowerCase().includes(needle),
        )),
  );

  return (
    <>
      <PageHeader title={t("errors.title")} description={t("errors.description")} />
      <div className="max-w-4xl space-y-3">
        <Input
          label={t("errors.search")}
          hideLabel
          icon="search"
          placeholder={t("errors.search")}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <SegmentedControl
          label={t("errors.category")}
          value={category}
          onChange={setCategory}
          segments={[{ id: "all", label: t("errors.all") }, ...categories.map((c) => ({ id: c, label: c }))]}
        />
        <p className="text-sm text-fg-muted" aria-live="polite">
          {t("errors.count", { count: visible.length })}
        </p>
        {codes.isPending ? (
          <Skeleton className="h-40 w-full" />
        ) : visible.length === 0 ? (
          <Card>
            <Empty icon="codes" title={t("errors.none")} />
          </Card>
        ) : (
          <div className="space-y-3">
            {visible.map((entry) => (
              <ErrorCodeEntry key={entry.code} entry={entry} />
            ))}
          </div>
        )}
      </div>
    </>
  );
}
