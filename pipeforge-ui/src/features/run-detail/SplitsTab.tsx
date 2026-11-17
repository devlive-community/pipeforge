/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { RunSplit } from "@pipeforge/client";
import { useRunSplits } from "@/api/queries";
import { useT } from "@/i18n";
import { Badge, Card, Empty, Table } from "@/ui";
import { LoadingPanel } from "@/ui/LoadingPanel";

/** Per node: how many of its splits were read, in the order the nodes first appear. */
export function splitProgress(splits: RunSplit[]): { node: string; finished: number; total: number }[] {
  const nodes = new Map<string, { node: string; finished: number; total: number }>();
  for (const split of splits) {
    const node = split.node ?? "";
    const entry = nodes.get(node) ?? { node, finished: 0, total: 0 };
    entry.total++;
    if (split.state === "FINISHED") entry.finished++;
    nodes.set(node, entry);
  }
  return [...nodes.values()];
}

/** The splits of a run's sources: which reader has each and whether it was read. */
export function SplitsTab({ id, live }: { id: string; live: boolean }) {
  const t = useT();
  const splits = useRunSplits(id, live);
  if (splits.isPending) return <LoadingPanel className="min-h-32" />;
  if (splits.isError) {
    return (
      <Card>
        <p className="text-sm text-danger">{t("runDetail.splitList.failed", { message: splits.error.message })}</p>
      </Card>
    );
  }
  const list = [...(splits.data ?? [])].sort(
    (a, b) =>
      (a.node ?? "").localeCompare(b.node ?? "") ||
      (a.subtask ?? 0) - (b.subtask ?? 0) ||
      (a.split ?? "").localeCompare(b.split ?? ""),
  );
  if (list.length === 0) {
    return (
      <Card>
        <Empty icon="source" title={t("runDetail.splitList.none")} description={t("runDetail.splitList.noneHint")} />
      </Card>
    );
  }
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-3">
        {splitProgress(list).map((node) => (
          <Card key={node.node} className="min-w-48 flex-1">
            <div className="font-mono text-sm text-fg">{node.node}</div>
            <div className="mt-1 text-sm text-fg-muted">
              {t("runDetail.splitList.progress", { finished: node.finished, total: node.total })}
            </div>
            <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-surface-3">
              <div
                className="h-full rounded-full bg-success"
                style={{ width: `${Math.round((node.finished / node.total) * 100)}%` }}
              />
            </div>
          </Card>
        ))}
      </div>
      <Card>
        <Table
          caption={t("runDetail.splitList.caption")}
          rows={list}
          rowKey={(split, index) => `${split.node ?? ""}/${split.split ?? index}`}
          columns={[
            {
              key: "node",
              header: t("runDetail.splitList.node"),
              cell: (split) => <span className="font-mono">{split.node}</span>,
            },
            {
              key: "split",
              header: t("runDetail.splitList.split"),
              cell: (split) => <span className="break-all font-mono text-xs">{split.split}</span>,
            },
            {
              key: "reader",
              header: t("runDetail.splitList.reader"),
              cell: (split) => `#${split.subtask ?? 0}`,
              className: "tabular-nums",
            },
            {
              key: "state",
              header: t("runDetail.splitList.state"),
              cell: (split) =>
                split.state === "FINISHED" ? (
                  <Badge tone="success">{t("runDetail.splitList.finished")}</Badge>
                ) : (
                  <Badge tone="info">{t("runDetail.splitList.assigned")}</Badge>
                ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
