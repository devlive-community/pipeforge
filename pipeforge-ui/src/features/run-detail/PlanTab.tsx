/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { NodeMetrics, RunMetrics } from "@pipeforge/client";
import { useMemo } from "react";
import { readStructure } from "@/features/job-editor/structure";
import { formatCount } from "@/features/runs/format";
import { useT } from "@/i18n";
import { usePreferences } from "@/state/preferences";
import { Card, DagCanvas, DescriptionList, Empty, type DagNode } from "@/ui";
import type { RunAnalysis } from "./analysis";
import { BottleneckCard } from "./Bottleneck";

/** Bytes in the largest binary unit below the value, one decimal: 512 B, 1.5 KiB, 3.2 GiB. */
export function formatBytes(bytes: number | null | undefined, language: string): string {
  if (bytes === null || bytes === undefined) return "–";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${new Intl.NumberFormat(language, { maximumFractionDigits: unit === 0 ? 0 : 1 }).format(value)} ${units[unit]}`;
}

/** The width of an edge for a rate: 1.5 for nothing, 8 for the fastest edge of the run. */
export function edgeWidthFor(rate: number | undefined, fastest: number): number | undefined {
  if (rate === undefined || fastest <= 0) return undefined;
  return 1.5 + 6.5 * Math.min(1, rate / fastest);
}

/**
 * The execution graph of a run with its counters and rates on it: each node shows the rows it
 * gave (a sink the rows it took) and its rate, edges are as thick as the rate on them, the
 * bottleneck is marked. Choosing a node shows its counters next to the graph.
 */
export function PlanTab({
  definition,
  metrics,
  analysis,
  selected,
  onSelect,
}: {
  definition: string | undefined;
  metrics: RunMetrics | undefined;
  analysis: RunAnalysis | undefined;
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const structure = useMemo(() => (definition ? readStructure(definition) : []), [definition]);
  const counters = new Map<string, NodeMetrics>((metrics?.nodes ?? []).map((n) => [n.node ?? "", n]));
  const rateOf = new Map((analysis?.rates ?? []).map((r) => [r.node, r]));
  const fastest = Math.max(0, ...(analysis?.rates ?? []).map((r) => r.rowsOutPerSecond));
  const bottleneck = analysis?.bottleneck.node ?? null;
  const compact = new Intl.NumberFormat(language, { notation: "compact", maximumFractionDigits: 1 });

  if (structure.length === 0) {
    return (
      <Card>
        <Empty icon="topology" title={t("runDetail.plan.noDefinition")} />
      </Card>
    );
  }

  const nodes: DagNode[] = structure.map((node) => {
    const counter = counters.get(node.id);
    const rate = rateOf.get(node.id);
    const sink = node.kind === "sink";
    const rows = sink ? counter?.rowsIn : counter?.rowsOut;
    const perSecond = sink ? rate?.rowsInPerSecond : rate?.rowsOutPerSecond;
    const kind = t(`editor.kinds.${node.kind}`);
    const isBottleneck = node.id === bottleneck;
    return {
      id: node.id,
      kind: node.kind,
      detail: node.type,
      inputs: node.inputs,
      label: t(isBottleneck ? "runDetail.plan.nodeBottleneck" : "editor.nodeLabel", {
        id: node.id,
        kind,
        type: node.type,
      }),
      metric:
        rows === undefined
          ? undefined
          : t("runDetail.plan.nodeMetric", {
              rows: compact.format(rows),
              rate: compact.format(Math.round(perSecond ?? 0)),
            }),
      emphasis: isBottleneck,
      selected: node.id === selected,
    };
  });

  const chosen = structure.find((n) => n.id === selected);
  const counter = chosen ? counters.get(chosen.id) : undefined;
  const rate = chosen ? rateOf.get(chosen.id) : undefined;
  return (
    <div className="space-y-4">
      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <Card flush className="overflow-hidden">
          <DagCanvas
            className="h-96"
            nodes={nodes}
            label={t("runDetail.plan.graph")}
            labels={{ zoomIn: t("editor.zoomIn"), zoomOut: t("editor.zoomOut"), fit: t("editor.fit") }}
            onSelect={onSelect}
            edgeWidth={(from) => edgeWidthFor(rateOf.get(from)?.rowsOutPerSecond, fastest)}
          />
        </Card>
        <Card title={chosen ? chosen.id : t("runDetail.plan.node")}>
          {chosen ? (
            <>
              <p className="mb-3 text-sm text-fg-muted">
                {t(`editor.kinds.${chosen.kind}`)} · <span className="font-mono">{chosen.type}</span>
              </p>
              {chosen.id === bottleneck ? (
                <p className="mb-3 rounded-control bg-warning-soft px-2 py-1 text-sm text-fg">
                  {t("runDetail.plan.isBottleneck")}
                </p>
              ) : null}
              <DescriptionList
                items={[
                  { label: t("runDetail.rowsIn"), value: formatCount(counter?.rowsIn, language) },
                  { label: t("runDetail.rowsOut"), value: formatCount(counter?.rowsOut, language) },
                  { label: t("runDetail.plan.filtered"), value: formatCount(counter?.rowsFiltered, language) },
                  { label: t("runDetail.plan.expanded"), value: formatCount(counter?.rowsExpanded, language) },
                  { label: t("runDetail.dirty"), value: formatCount(counter?.rowsDirty, language) },
                  { label: t("runDetail.plan.bytesIn"), value: formatBytes(counter?.bytesIn, language) },
                  { label: t("runDetail.plan.bytesOut"), value: formatBytes(counter?.bytesOut, language) },
                  {
                    label: t("runDetail.plan.rateIn"),
                    value: rate
                      ? t("runDetail.perSecond", { rate: formatCount(Math.round(rate.rowsInPerSecond), language) })
                      : "–",
                  },
                  {
                    label: t("runDetail.plan.rateOut"),
                    value: rate
                      ? t("runDetail.perSecond", { rate: formatCount(Math.round(rate.rowsOutPerSecond), language) })
                      : "–",
                  },
                ]}
              />
            </>
          ) : (
            <p className="text-sm text-fg-muted">{t("runDetail.plan.choose")}</p>
          )}
        </Card>
      </div>
      <BottleneckCard analysis={analysis} />
    </div>
  );
}
