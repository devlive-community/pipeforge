/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Run, RunState } from "@pipeforge/client";
import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import { useRuns } from "@/api/queries";
import { useT } from "@/i18n";
import { usePreferences } from "@/state/preferences";
import {
  Badge,
  Button,
  Card,
  Empty,
  Input,
  PageHeader,
  SegmentedControl,
  Table,
  Tooltip,
  type BadgeTone,
  type Column,
} from "@/ui";
import { LoadingPanel } from "@/ui/LoadingPanel";
import { formatCount, formatDuration, formatTime, runDuration } from "./format";

/** The filter groups of the page and the states each one lists. */
export const GROUPS: Record<string, RunState[]> = {
  all: [],
  active: ["QUEUE", "RUNNING", "CANCELLING"],
  succeeded: ["SUCCESS"],
  failed: ["FAILED", "TIMEOUT"],
  cancelled: ["CANCELLED"],
};

const TONES: Record<RunState, BadgeTone> = {
  QUEUE: "neutral",
  RUNNING: "info",
  CANCELLING: "warning",
  SUCCESS: "success",
  FAILED: "danger",
  TIMEOUT: "danger",
  CANCELLED: "neutral",
};

/** The state of a run as a badge with its tone. */
export function RunStateBadge({ state }: { state: RunState }) {
  const t = useT();
  return <Badge tone={TONES[state]}>{t(`runs.states.${state}`)}</Badge>;
}

const PAGE_SIZE = 50;

/** The runs of jobs, newest first, by state group and job; the filters are in the address. */
export function RunsPage() {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const [params, setParams] = useSearchParams();
  const group = params.get("state") ?? "all";
  const job = params.get("job") ?? "";
  // the tokens of the pages before the current one; the first page has none
  const [pages, setPages] = useState<string[]>([]);
  const page = pages.at(-1);
  const runs = useRuns({
    status: GROUPS[group] ?? [],
    job: job || undefined,
    limit: PAGE_SIZE,
    page,
  });

  const filter = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value && !(key === "state" && value === "all")) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
    setPages([]);
  };

  const columns: Column<Run>[] = [
    {
      key: "job",
      header: t("runs.columns.job"),
      cell: (run) => (
        <div>
          <Link to={`/runs/${run.id ?? ""}`} className="font-medium text-primary hover:underline">
            {run.job}
          </Link>
          <div className="font-mono text-xs text-fg-subtle">{(run.id ?? "").slice(0, 8)}</div>
        </div>
      ),
    },
    { key: "state", header: t("runs.columns.state"), cell: (run) => <RunStateBadge state={run.status as RunState} /> },
    {
      key: "submitted",
      header: t("runs.columns.submitted"),
      cell: (run) => formatTime(run.submittedAt, language),
      className: "whitespace-nowrap",
    },
    {
      key: "duration",
      header: t("runs.columns.duration"),
      cell: (run) => formatDuration(runDuration(run)),
      className: "whitespace-nowrap tabular-nums",
    },
    {
      key: "read",
      header: t("runs.columns.read"),
      cell: (run) => formatCount(run.rowsRead, language),
      className: "text-right tabular-nums",
    },
    {
      key: "written",
      header: t("runs.columns.written"),
      cell: (run) => formatCount(run.rowsWritten, language),
      className: "text-right tabular-nums",
    },
    {
      key: "error",
      header: t("runs.columns.error"),
      cell: (run) =>
        run.error ? (
          <Tooltip content={run.error.message ?? ""}>
            {/* focusable, so that keyboard users reach the message too */}
            <span tabIndex={0} className="whitespace-nowrap font-mono text-xs text-danger">
              {run.error.code}
            </span>
          </Tooltip>
        ) : null,
    },
  ];

  const list = runs.data?.runs ?? [];
  const filtered = group !== "all" || job !== "";
  return (
    <>
      <PageHeader title={t("runs.title")} description={t("runs.description")} />
      <Card>
        <div className="mb-3 flex flex-wrap items-end gap-3">
          <SegmentedControl
            label={t("runs.filter")}
            value={group}
            onChange={(id) => filter("state", id)}
            segments={Object.keys(GROUPS).map((id) => ({ id, label: t(`runs.groups.${id as "all"}`) }))}
          />
          <Input
            label={t("runs.job")}
            hideLabel
            icon="search"
            placeholder={t("runs.jobPlaceholder")}
            value={job}
            onChange={(e) => filter("job", e.target.value)}
            className="w-56"
          />
        </div>
        {runs.isPending ? (
          <LoadingPanel className="min-h-32" />
        ) : runs.isError ? (
          <p className="text-sm text-danger">{t("runs.failed", { message: (runs.error as Error).message })}</p>
        ) : list.length === 0 && !filtered && pages.length === 0 ? (
          <Empty icon="play" title={t("runs.none")} description={t("runs.noneHint")} />
        ) : (
          <Table
            caption={t("runs.caption")}
            columns={columns}
            rows={list}
            rowKey={(run, index) => run.id ?? String(index)}
            empty={t("runs.noneFiltered")}
          />
        )}
        {pages.length > 0 || runs.data?.next ? (
          <div className="mt-3 flex justify-end gap-2">
            <Button
              size="sm"
              variant="secondary"
              disabled={pages.length === 0}
              onClick={() => setPages(pages.slice(0, -1))}
            >
              {t("runs.previous")}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={!runs.data?.next}
              onClick={() => {
                const next = runs.data?.next;
                if (next) setPages([...pages, next]);
              }}
            >
              {t("runs.next")}
            </Button>
          </div>
        ) : null}
      </Card>
    </>
  );
}
