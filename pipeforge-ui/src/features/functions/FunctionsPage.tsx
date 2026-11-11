/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { components } from "@pipeforge/client";
import { useMemo, useState } from "react";
import { useFunctions } from "@/api/queries";
import { useScrollToHash } from "@/app/useScrollToHash";
import { useT } from "@/i18n";
import { Badge, Button, Card, Empty, Input, PageHeader, SegmentedControl, Skeleton } from "@/ui";
import { INITIAL_PLAYGROUND, Playground, type PlaygroundState } from "./Playground";

type FunctionInfo = components["schemas"]["FunctionInfo"];
const CATEGORIES = ["comparison", "numeric", "string", "datetime", "conversion"] as const;

function FunctionCard({ fn, onTry }: { fn: FunctionInfo; onTry: (expression: string) => void }) {
  const t = useT();
  const name = fn.name ?? "";
  return (
    <article
      id={name.toLowerCase()}
      tabIndex={-1}
      className="scroll-mt-4 rounded-card border border-border p-4 outline-none target:border-primary"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="font-mono text-sm font-semibold text-fg">{name}</h2>
        <Badge>{t(`functions.categories.${fn.category as (typeof CATEGORIES)[number]}`)}</Badge>
        {fn.deterministic === false ? <Badge tone="warning">{t("functions.nondeterministic")}</Badge> : null}
      </div>
      <p className="mt-2 text-sm text-fg">{fn.description}</p>
      <div className="mt-2 space-y-0.5 font-mono text-xs text-fg-muted">
        {fn.syntax ? <p>{fn.syntax}</p> : null}
        {(fn.signatures ?? []).map((s) => (
          <p key={s}>{s}</p>
        ))}
      </div>
      <p className="mt-2 text-sm text-fg-muted">
        <span className="font-medium text-fg">{t("functions.nulls")}: </span>
        {fn.nulls ?? t("functions.nullDefault")}
      </p>
      {fn.examples && fn.examples.length > 0 ? (
        <table className="mt-3 w-full text-sm">
          <caption className="sr-only">{t("functions.examples")}</caption>
          <tbody>
            {fn.examples.map((example) => (
              <tr key={example.expression} className="border-t border-border">
                <td className="py-1.5 pr-3 font-mono text-xs text-fg">{example.expression}</td>
                <td className="py-1.5 pr-3 font-mono text-xs text-fg-muted">→ {example.result}</td>
                <td className="py-1 text-right">
                  <Button size="sm" variant="ghost" onClick={() => onTry(example.expression ?? "")}>
                    {t("functions.tryIt")}
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </article>
  );
}

/** The built-in functions with search and categories, next to the expression playground. */
export function FunctionsPage() {
  const t = useT();
  const functions = useFunctions();
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("all");
  const [playground, setPlayground] = useState<PlaygroundState>(INITIAL_PLAYGROUND);
  useScrollToHash(Boolean(functions.data));

  const needle = query.trim().toLowerCase();
  const all = useMemo(() => functions.data ?? [], [functions.data]);
  const visible = all.filter(
    (f) =>
      (category === "all" || f.category === category) &&
      (needle === "" ||
        [f.name ?? "", f.description ?? "", ...(f.signatures ?? [])].some((text) =>
          text.toLowerCase().includes(needle),
        )),
  );
  const completable = useMemo(
    () => all.map((f) => ({ name: f.name ?? "", signatures: f.signatures, description: f.description })),
    [all],
  );

  return (
    <>
      <PageHeader title={t("functions.title")} description={t("functions.description")} />
      <div className="grid items-start gap-4 xl:grid-cols-[1fr_minmax(0,32rem)]">
        <div className="min-w-0 space-y-3">
          <Input
            label={t("functions.search")}
            hideLabel
            icon="search"
            placeholder={t("functions.search")}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <SegmentedControl
            label={t("functions.category")}
            value={category}
            onChange={setCategory}
            segments={[
              { id: "all", label: t("functions.all") },
              ...CATEGORIES.map((c) => ({ id: c, label: t(`functions.categories.${c}`) })),
            ]}
          />
          <p className="text-sm text-fg-muted" aria-live="polite">
            {t("functions.count", { count: visible.length })}
          </p>
          {functions.isPending ? (
            <Skeleton className="h-40 w-full" />
          ) : visible.length === 0 ? (
            <Card>
              <Empty icon="functions" title={t("functions.none")} />
            </Card>
          ) : (
            <div className="space-y-3">
              {visible.map((fn) => (
                <FunctionCard
                  key={fn.name}
                  fn={fn}
                  onTry={(expression) => setPlayground({ ...playground, expression })}
                />
              ))}
            </div>
          )}
        </div>
        <div className="xl:sticky xl:top-4">
          <Playground state={playground} onChange={setPlayground} functions={completable} />
        </div>
      </div>
    </>
  );
}
