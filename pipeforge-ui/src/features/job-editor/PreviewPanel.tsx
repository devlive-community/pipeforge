/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { PreviewResult } from "@pipeforge/client";
import { formatCount, formatDuration } from "@/features/runs/format";
import { useT } from "@/i18n";
import { usePreferences } from "@/state/preferences";
import { Badge, Button, DropdownMenu, Table, type BadgeTone } from "@/ui";
import type { StructureNode } from "./structure";

/** The id of the dry run in the node menu: the whole job up to its sinks. */
export const DRY_RUN = "";

const OUTCOME_TONES: Record<NonNullable<PreviewResult["outcome"]>, BadgeTone> = {
  COMPLETE: "success",
  LIMIT: "info",
  TIMEOUT: "warning",
  FAILED: "danger",
};

/** A value of a previewed row as text: NULL, JSON for structures, the value otherwise. */
export function cellText(value: unknown): string {
  if (value === null || value === undefined) return "NULL";
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

export interface PreviewState {
  /** The node previewed, or {@link DRY_RUN}. */
  node: string;
  /** The definition the preview ran on, to tell when the job changed since. */
  definition: string;
  result?: PreviewResult;
  error?: Error;
  pending: boolean;
}

/**
 * The data preview of the job editor: the rows a node produces, or for a dry run the rows that
 * would reach the sinks, from a run of that part of the job in the server that writes nothing.
 */
export function PreviewPanel({
  structure,
  definition,
  node,
  onNode,
  onRun,
  state,
  canRun,
}: {
  structure: StructureNode[];
  definition: string;
  node: string;
  onNode: (node: string) => void;
  onRun: () => void;
  state: PreviewState | null;
  canRun: boolean;
}) {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const choices = [
    { id: DRY_RUN, label: t("editor.preview.dryRun") },
    ...structure
      .filter((n) => n.kind !== "sink")
      .map((n) => ({ id: n.id, label: t("editor.preview.node", { id: n.id, type: n.type }) })),
  ];
  const chosen = choices.find((c) => c.id === node) ?? choices[0]!;
  const result = state?.result;
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-2">
        <DropdownMenu
          label={t("editor.preview.what")}
          align="start"
          value={chosen.id}
          onSelect={onNode}
          items={choices}
          trigger={(props) => (
            <Button {...props} size="sm" icon="chevronDown" aria-label={t("editor.preview.what")}>
              {chosen.label}
            </Button>
          )}
        />
        <Button size="sm" variant="primary" icon="play" loading={state?.pending} disabled={!canRun} onClick={onRun}>
          {t("editor.preview.run")}
        </Button>
        {!canRun ? <span className="text-sm text-fg-muted">{t("editor.preview.invalid")}</span> : null}
        {result && !state?.pending ? (
          <span className="ml-auto flex flex-wrap items-center gap-2 text-sm text-fg-muted">
            {result.outcome ? (
              <Badge tone={OUTCOME_TONES[result.outcome]}>{t(`editor.preview.outcomes.${result.outcome}`)}</Badge>
            ) : null}
            {t("editor.preview.summary", {
              rows: formatCount(result.rowsRead, language),
              elapsed: formatDuration(result.elapsedMs),
            })}
          </span>
        ) : null}
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-4">
        {state?.pending ? (
          <p role="status" className="text-sm text-fg-muted">
            {t("editor.preview.running")}
          </p>
        ) : state?.error ? (
          <p className="text-sm text-danger">{t("editor.preview.failed", { message: state.error.message })}</p>
        ) : !result ? (
          <p className="text-sm text-fg-muted">{t("editor.preview.hint")}</p>
        ) : (
          <div className="space-y-4">
            {state.definition !== definition ? (
              <p className="rounded-control bg-warning-soft px-2 py-1 text-sm text-fg">{t("editor.preview.stale")}</p>
            ) : null}
            {result.error ? (
              <p className="text-sm text-danger">
                <span className="font-mono text-xs">{result.error.code}</span> {result.error.message}
              </p>
            ) : null}
            {(result.tables ?? []).map((table, index) => {
              const columns = table.columns ?? [];
              const rows = table.rows ?? [];
              return (
                <section key={table.table ?? index}>
                  {(result.tables ?? []).length > 1 || table.table ? (
                    <h3 className="mb-1 font-mono text-sm text-fg">{table.table}</h3>
                  ) : null}
                  <Table
                    caption={t("editor.preview.caption", { table: table.table ?? chosen.label })}
                    rows={rows}
                    rowKey={(_, row) => String(row)}
                    empty={t("editor.preview.noRows")}
                    columns={columns.map((column, at) => ({
                      key: `${at}`,
                      header: (
                        <span className="whitespace-nowrap">
                          {column.name} <span className="font-mono text-xs text-fg-subtle">{column.type}</span>
                        </span>
                      ),
                      cell: (row: unknown[]) => {
                        const value = row[at];
                        return (
                          <span
                            className={
                              value === null || value === undefined
                                ? "font-mono text-xs text-fg-subtle"
                                : "whitespace-nowrap font-mono text-xs"
                            }
                          >
                            {cellText(value)}
                          </span>
                        );
                      },
                    }))}
                  />
                  {table.truncated ? (
                    <p className="mt-1 text-xs text-fg-subtle">
                      {t("editor.preview.truncated", { count: rows.length })}
                    </p>
                  ) : null}
                </section>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
