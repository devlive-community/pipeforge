/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { ApiError, type ClusterView } from "@pipeforge/client";
import { useCluster } from "@/api/queries";
import { formatBytes, formatCount, formatTime } from "@/features/runs/format";
import { useT } from "@/i18n";
import { usePreferences } from "@/state/preferences";
import { Badge, Card, DescriptionList, Empty, PageHeader } from "@/ui";
import { cx } from "@/ui/cx";
import { LoadingPanel } from "@/ui/LoadingPanel";

type Worker = NonNullable<ClusterView["workers"]>[number];

/** After this long without a heartbeat a worker is shown as late; the default interval is 5 s. */
export const LATE_MS = 15_000;

/** The jobs holding slots of a worker, with how many each holds, most first. */
export function slotJobs(worker: Worker): { job: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const slot of worker.slots ?? []) {
    if (slot.allocated && slot.job) counts.set(slot.job, (counts.get(slot.job) ?? 0) + 1);
  }
  return [...counts.entries()]
    .map(([job, count]) => ({ job, count }))
    .sort((a, b) => b.count - a.count || a.job.localeCompare(b.job));
}

/** A share from 0 to 1 as a bar. */
function UsageBar({ share }: { share: number }) {
  return (
    <div className="h-1.5 overflow-hidden rounded-full bg-surface-3">
      <div className="h-full rounded-full bg-primary" style={{ width: `${Math.round(share * 100)}%` }} />
    </div>
  );
}

function WorkerCard({ worker }: { worker: Worker }) {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const slots = worker.slots ?? [];
  const used = slots.filter((s) => s.allocated).length;
  const late = (worker.sinceHeartbeatMs ?? 0) > LATE_MS;
  const labels = Object.entries(worker.labels ?? {});
  const jobs = slotJobs(worker);
  return (
    <Card
      title={<span className="font-mono">{worker.id}</span>}
      actions={late ? <Badge tone="warning">{t("cluster.late")}</Badge> : null}
    >
      {late ? <p className="mb-3 text-sm text-warning">{t("cluster.lateHint")}</p> : null}
      <div
        role="img"
        aria-label={t("cluster.slotGrid", { worker: worker.id ?? "", used, total: slots.length })}
        className="flex flex-wrap gap-1"
      >
        {slots.map((slot) => (
          <span key={slot.index} className={cx("size-4 rounded-sm", slot.allocated ? "bg-primary" : "bg-surface-3")} />
        ))}
      </div>
      <p className="mt-2 text-sm text-fg-muted">{t("cluster.slotsUsed", { used, total: slots.length })}</p>
      {jobs.length > 0 ? (
        <ul className="mt-1 space-y-0.5 text-sm text-fg">
          {jobs.map((entry) => (
            <li key={entry.job}>{t("cluster.slotJobs", { job: entry.job, count: entry.count })}</li>
          ))}
        </ul>
      ) : null}
      <div className="mt-4">
        <DescriptionList
          items={[
            { label: t("cluster.worker.rpc"), value: <span className="font-mono text-xs">{worker.rpcAddress}</span> },
            { label: t("cluster.worker.data"), value: <span className="font-mono text-xs">{worker.dataAddress}</span> },
            { label: t("cluster.worker.group"), value: worker.resourceGroup || "–" },
            {
              label: t("cluster.worker.labels"),
              value:
                labels.length > 0 ? (
                  <span className="flex flex-wrap gap-1">
                    {labels.map(([key, value]) => (
                      <Badge key={key}>{`${key}=${value}`}</Badge>
                    ))}
                  </span>
                ) : (
                  <span className="text-fg-muted">{t("cluster.worker.noLabels")}</span>
                ),
            },
            { label: t("cluster.worker.tasks"), value: formatCount(worker.tasksRunning, language) },
            { label: t("cluster.worker.cores"), value: formatCount(worker.cpuCores, language) },
            { label: t("cluster.worker.heap"), value: formatBytes(worker.taskHeapBytes, language) },
            { label: t("cluster.worker.managed"), value: formatBytes(worker.managedBytes, language) },
            { label: t("cluster.worker.network"), value: formatBytes(worker.networkBytes, language) },
            {
              label: t("cluster.worker.heartbeat"),
              value: (
                <span className={cx("tabular-nums", late && "text-warning")}>
                  {t("cluster.ago", { seconds: Math.round((worker.sinceHeartbeatMs ?? 0) / 1000) })}
                </span>
              ),
            },
            { label: t("cluster.worker.version"), value: worker.version ?? "–" },
          ]}
        />
      </div>
    </Card>
  );
}

/** The master, the slots of the cluster and each worker with its slots and resources. */
export function ClusterPage() {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const cluster = useCluster();
  const header = <PageHeader title={t("cluster.title")} description={t("cluster.description")} />;

  if (cluster.isPending) {
    return (
      <>
        {header}
        <LoadingPanel className="min-h-48" />
      </>
    );
  }
  if (cluster.isError) {
    const error = cluster.error;
    const code = error instanceof ApiError ? error.code : "";
    return (
      <>
        {header}
        <Card>
          {code === "PF-CLUS-0015" ? (
            <Empty icon="server" title={t("cluster.noMaster")} description={t("cluster.noMasterHint")} />
          ) : (
            <Empty
              icon="alert"
              title={code === "PF-CLUS-0008" ? t("cluster.unreachable") : t("cluster.failed", { message: "" })}
              description={error.message}
            />
          )}
        </Card>
      </>
    );
  }
  const view = cluster.data;
  const workers = [...(view.workers ?? [])].sort((a, b) => (a.id ?? "").localeCompare(b.id ?? ""));
  return (
    <>
      {header}
      <div className="mb-4 grid gap-4 md:grid-cols-3">
        <Card title={t("cluster.master")}>
          <DescriptionList
            items={[
              {
                label: t("cluster.address"),
                value: <span className="font-mono text-xs">{view.master?.address}</span>,
              },
              { label: t("cluster.version"), value: view.master?.version ?? "–" },
              { label: t("cluster.protocol"), value: view.master?.protocol ?? "–" },
              { label: t("cluster.started"), value: formatTime(view.master?.startedAt, language) },
            ]}
          />
        </Card>
        <Card title={t("cluster.slots")}>
          <p className="text-2xl font-semibold tabular-nums text-fg">{Math.round((view.usage ?? 0) * 100)}%</p>
          <p className="mb-2 text-sm text-fg-muted">
            {t("cluster.slotsUsed", { used: view.slotsUsed ?? 0, total: view.slots ?? 0 })}
          </p>
          <UsageBar share={view.usage ?? 0} />
        </Card>
        <Card title={t("cluster.workers")}>
          <p className="text-2xl font-semibold tabular-nums text-fg">{workers.length}</p>
          <p className="text-sm text-fg-muted">{t("cluster.workerCount", { count: workers.length })}</p>
        </Card>
      </div>
      {workers.length === 0 ? (
        <Card>
          <Empty icon="server" title={t("cluster.noWorkers")} description={t("cluster.noWorkersHint")} />
        </Card>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {workers.map((worker) => (
            <WorkerCard key={worker.id} worker={worker} />
          ))}
        </div>
      )}
    </>
  );
}
