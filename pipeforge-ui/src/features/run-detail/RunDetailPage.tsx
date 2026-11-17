/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { ApiError, type RunMetrics, type RunState } from "@pipeforge/client";
import { Link, useParams, useSearchParams } from "react-router";
import { useRun, useRunAnalysis, useRunHistory, useRunMetrics } from "@/api/queries";
import { LineChart } from "@/charts";
import { formatCount, formatDuration, formatTime, runDuration } from "@/features/runs/format";
import { RunStateBadge } from "@/features/runs/RunsPage";
import { useT } from "@/i18n";
import { usePreferences } from "@/state/preferences";
import { Card, DescriptionList, Empty, PageHeader, Table, Tabs } from "@/ui";
import { LoadingPanel } from "@/ui/LoadingPanel";
import { evidenceValue, isAdviceCode, rates, waitShare, type RunAnalysis } from "./analysis";

const TERMINAL: RunState[] = ["SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"];

/** A share from 0 to 1 as a bar with its percentage. */
function ShareBar({ label, share }: { label: string; share: number }) {
  const percent = Math.round(share * 100);
  return (
    <div>
      <div className="flex justify-between text-sm">
        <span className="text-fg-muted">{label}</span>
        <span className="tabular-nums text-fg">{percent}%</span>
      </div>
      <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-surface-3">
        <div className="h-full rounded-full bg-primary" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

function Overview({ metrics, analysis }: { metrics: RunMetrics | undefined; analysis: RunAnalysis | undefined }) {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const total = metrics?.splitsTotal ?? 0;
  const finished = metrics?.splitsFinished ?? 0;
  const bottleneck = analysis?.bottleneck;
  return (
    <div className="grid items-start gap-4 lg:grid-cols-2">
      <Card title={t("runDetail.rows")}>
        <DescriptionList
          items={[
            {
              label: t("runDetail.read"),
              value: (
                <span className="tabular-nums">
                  {formatCount(metrics?.rowsRead, language)} ·{" "}
                  {t("runDetail.perSecond", {
                    rate: formatCount(Math.round(metrics?.readRowsPerSecond ?? 0), language),
                  })}
                </span>
              ),
            },
            {
              label: t("runDetail.written"),
              value: (
                <span className="tabular-nums">
                  {formatCount(metrics?.rowsWritten, language)} ·{" "}
                  {t("runDetail.perSecond", {
                    rate: formatCount(Math.round(metrics?.writeRowsPerSecond ?? 0), language),
                  })}
                </span>
              ),
            },
            { label: t("runDetail.inFlight"), value: formatCount(metrics?.rowsInFlight, language) },
            { label: t("runDetail.dirty"), value: formatCount(metrics?.rowsDirty, language) },
          ]}
        />
        <h3 className="mt-4 text-xs font-medium text-fg-muted">{t("runDetail.progress")}</h3>
        {total > 0 ? (
          <div className="mt-1">
            <ShareBar label={t("runDetail.splits", { finished, total })} share={finished / total} />
          </div>
        ) : (
          <p className="mt-1 text-sm text-fg-muted">{t("runDetail.noSplits")}</p>
        )}
      </Card>
      <Card title={t("runDetail.waits")}>
        {metrics ? (
          <div className="space-y-3">
            <ShareBar label={t("runDetail.readerWait")} share={waitShare(metrics, metrics.readerWaitMs)} />
            <ShareBar label={t("runDetail.writerWait")} share={waitShare(metrics, metrics.writerWaitMs)} />
            <ShareBar label={t("runDetail.throttleWait")} share={waitShare(metrics, metrics.throttleWaitMs)} />
            <ShareBar label={t("runDetail.backpressure")} share={waitShare(metrics, metrics.backpressuredMs)} />
          </div>
        ) : (
          <LoadingPanel className="min-h-24" />
        )}
      </Card>
      <Card title={t("runDetail.bottleneck")} className="lg:col-span-2">
        {bottleneck ? (
          <div className="space-y-3">
            <div>
              <p className="font-medium text-fg">
                <span>{t(`runDetail.kinds.${bottleneck.kind}`)}</span>
                {bottleneck.node ? (
                  <span className="ml-2 font-mono text-sm text-fg-muted">
                    {t("runDetail.at", { node: bottleneck.node })}
                  </span>
                ) : null}
              </p>
              <p className="mt-0.5 text-sm text-fg-muted">{t(`runDetail.kindHints.${bottleneck.kind}`)}</p>
            </div>
            {bottleneck.evidence.length > 0 ? (
              <div>
                <h3 className="text-xs font-medium text-fg-muted">{t("runDetail.evidence")}</h3>
                <ul className="mt-1 flex flex-wrap gap-2 text-sm">
                  {bottleneck.evidence.map((e) => (
                    <li key={`${e.metric}/${e.node ?? ""}`} className="rounded-control bg-surface-2 px-2 py-0.5">
                      <span className="font-mono text-xs text-fg-muted">
                        {e.node ? `${e.node} · ` : ""}
                        {e.metric}
                      </span>{" "}
                      <span className="tabular-nums text-fg">{evidenceValue(e.metric, e.value)}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
            {bottleneck.advice.length > 0 ? (
              <div>
                <h3 className="text-xs font-medium text-fg-muted">{t("runDetail.advice")}</h3>
                <ul className="mt-1 list-disc space-y-0.5 pl-5 text-sm text-fg">
                  {bottleneck.advice.map((a) => (
                    <li key={a.code}>
                      {isAdviceCode(a.code)
                        ? t(`runDetail.adviceCodes.${a.code}`, { node: bottleneck.node ?? "" })
                        : a.text}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </div>
        ) : (
          <p className="text-sm text-fg-muted">{t("runDetail.noAnalysis")}</p>
        )}
      </Card>
    </div>
  );
}

function Metrics({ id, live, metrics }: { id: string; live: boolean; metrics: RunMetrics | undefined }) {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const history = useRunHistory(id, live);
  const samples = history.data ?? [];
  const { read, write } = rates(samples);
  const time = (ms: number) =>
    new Intl.DateTimeFormat(language, { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(ms);
  const count = (value: number) => new Intl.NumberFormat(language, { notation: "compact" }).format(value);
  const last = samples.at(-1);
  return (
    <div className="space-y-4">
      <Card title={t("runDetail.rates")}>
        <LineChart
          label={t("runDetail.rates")}
          summary={t("runDetail.ratesSummary", {
            read: formatCount(Math.round(read.at(-1)?.y ?? 0), language),
            write: formatCount(Math.round(write.at(-1)?.y ?? 0), language),
          })}
          series={[
            { id: "read", label: t("runDetail.read"), color: 1, points: read },
            { id: "write", label: t("runDetail.written"), color: 2, points: write },
          ]}
          formatY={count}
          formatX={time}
          empty={t("runDetail.noSamples")}
        />
      </Card>
      <Card title={t("runDetail.rowsChart")}>
        <LineChart
          label={t("runDetail.rowsChart")}
          summary={t("runDetail.rowsSummary", {
            read: formatCount(last?.rowsRead, language),
            written: formatCount(last?.rowsWritten, language),
          })}
          series={[
            {
              id: "read",
              label: t("runDetail.read"),
              color: 1,
              points: samples.map((s) => ({ x: Date.parse(s.at ?? ""), y: s.rowsRead ?? 0 })),
            },
            {
              id: "written",
              label: t("runDetail.written"),
              color: 2,
              points: samples.map((s) => ({ x: Date.parse(s.at ?? ""), y: s.rowsWritten ?? 0 })),
            },
          ]}
          formatY={count}
          formatX={time}
          empty={t("runDetail.noSamples")}
        />
      </Card>
      <Card title={t("runDetail.nodes")}>
        <Table
          caption={t("runDetail.nodes")}
          rows={metrics?.nodes ?? []}
          rowKey={(node, index) => node.node ?? String(index)}
          columns={[
            {
              key: "node",
              header: t("runDetail.node"),
              cell: (node) => <span className="font-mono">{node.node}</span>,
            },
            {
              key: "in",
              header: t("runDetail.rowsIn"),
              cell: (node) => formatCount(node.rowsIn, language),
              className: "text-right tabular-nums",
            },
            {
              key: "out",
              header: t("runDetail.rowsOut"),
              cell: (node) => formatCount(node.rowsOut, language),
              className: "text-right tabular-nums",
            },
          ]}
        />
      </Card>
    </div>
  );
}

/** A run: its facts, then its overview and metrics in tabs; the tab is in the address. */
export function RunDetailPage() {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const { id = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") === "metrics" ? "metrics" : "overview";
  const detail = useRun(id);
  const run = detail.data?.run;
  const live = run ? !TERMINAL.includes(run.status as RunState) : true;
  const metrics = useRunMetrics(id, live);
  const analysis = useRunAnalysis(id, live);

  if (detail.isPending) {
    return <LoadingPanel className="min-h-64" />;
  }
  if (detail.isError || !run) {
    const missing = detail.error instanceof ApiError && detail.error.status === 404;
    return (
      <Card>
        <Empty
          icon="alert"
          title={
            missing
              ? t("runDetail.notFound", { id })
              : t("runDetail.failed", { message: (detail.error as Error | null)?.message ?? "" })
          }
        />
      </Card>
    );
  }
  return (
    <>
      <PageHeader
        title={run.job}
        description={
          <Link to="/runs" className="text-primary hover:underline">
            ← {t("runDetail.back")}
          </Link>
        }
        actions={<RunStateBadge state={run.status as RunState} />}
      />
      <Card className="mb-4">
        <DescriptionList
          items={[
            { label: t("runDetail.facts.id"), value: <span className="font-mono text-xs">{run.id}</span> },
            { label: t("runDetail.facts.submitted"), value: formatTime(run.submittedAt, language) },
            { label: t("runDetail.facts.started"), value: formatTime(run.startedAt, language) },
            { label: t("runDetail.facts.ended"), value: formatTime(run.endedAt, language) },
            { label: t("runDetail.facts.duration"), value: formatDuration(runDuration(run)) },
            ...(run.error
              ? [
                  {
                    label: t("runDetail.facts.error"),
                    value: (
                      <span>
                        <span className="font-mono text-xs text-danger">{run.error.code}</span> {run.error.message}
                      </span>
                    ),
                  },
                ]
              : []),
          ]}
        />
      </Card>
      <Tabs
        label={run.job ?? ""}
        value={tab}
        onChange={(next) => {
          const changed = new URLSearchParams(params);
          if (next === "overview") changed.delete("tab");
          else changed.set("tab", next);
          setParams(changed, { replace: true });
        }}
        tabs={[
          {
            id: "overview",
            label: t("runDetail.tabs.overview"),
            content: (
              <Overview
                metrics={metrics.data}
                analysis={analysis.isError ? undefined : (analysis.data as unknown as RunAnalysis | undefined)}
              />
            ),
          },
          {
            id: "metrics",
            label: t("runDetail.tabs.metrics"),
            content: <Metrics id={id} live={live} metrics={metrics.data} />,
          },
        ]}
      />
    </>
  );
}
