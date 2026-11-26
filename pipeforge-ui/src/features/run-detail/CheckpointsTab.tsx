/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { RunCheckpoint } from "@pipeforge/client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/api/client";
import { useRunCheckpoints } from "@/api/queries";
import { formatBytes, formatDuration, formatTime } from "@/features/runs/format";
import { useT } from "@/i18n";
import { usePreferences } from "@/state/preferences";
import { Badge, Button, Card, DescriptionList, Empty, Input, Table, type BadgeTone } from "@/ui";
import { LoadingPanel } from "@/ui/LoadingPanel";

const STATUS_TONES: Record<string, BadgeTone> = {
  COMPLETED: "success",
  FAILED: "danger",
  IN_PROGRESS: "info",
};

/** How long a checkpoint took, from when it was taken to when it completed; null while it is not done. */
export function checkpointDuration(checkpoint: RunCheckpoint): number | null {
  if (!checkpoint.completedAt || !checkpoint.triggeredAt) return null;
  return Date.parse(checkpoint.completedAt) - Date.parse(checkpoint.triggeredAt);
}

/** The longest any task of a checkpoint waited for the barriers of its inputs; null without tasks. */
export function longestAlignment(checkpoint: RunCheckpoint): number | null {
  const tasks = checkpoint.tasks ?? [];
  return tasks.length === 0 ? null : Math.max(...tasks.map((task) => task.alignmentMs ?? 0));
}

/** What the checkpoints of a run add up to: the last completed one, and how many completed and failed. */
export function checkpointSummary(checkpoints: RunCheckpoint[]) {
  const completed = checkpoints.filter((c) => c.status === "COMPLETED");
  return {
    last: completed.find((c) => c.type === "CHECKPOINT") ?? completed[0],
    completed: completed.filter((c) => c.type === "CHECKPOINT").length,
    savepoints: completed.filter((c) => c.type === "SAVEPOINT").length,
    failed: checkpoints.filter((c) => c.status === "FAILED").length,
  };
}

/** Takes a savepoint of a running run, with an optional name. */
function SavepointForm({ id }: { id: string }) {
  const t = useT();
  const [name, setName] = useState("");
  const queries = useQueryClient();
  const savepoint = useMutation<RunCheckpoint, Error, string>({
    mutationFn: (named) => api.savepointRun(id, named.trim() || undefined),
    onSuccess: () => {
      setName("");
      void queries.invalidateQueries({ queryKey: ["run-checkpoints", id] });
    },
  });
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        savepoint.mutate(name);
      }}
    >
      <Input
        label={t("runDetail.checkpoints.savepointName")}
        placeholder={t("runDetail.checkpoints.savepointPlaceholder")}
        value={name}
        onChange={(event) => setName(event.target.value)}
        className="w-64"
        disabled={savepoint.isPending}
      />
      <Button type="submit" variant="primary" icon="bookmark" loading={savepoint.isPending}>
        {savepoint.isPending ? t("runDetail.checkpoints.taking") : t("runDetail.checkpoints.take")}
      </Button>
      <div role="status" className="basis-full text-sm">
        {savepoint.isSuccess ? (
          <span className="text-success">
            {t("runDetail.checkpoints.taken", { id: savepoint.data.id ?? 0, path: savepoint.data.path ?? "" })}
          </span>
        ) : savepoint.isError ? (
          <span className="text-danger">
            {t("runDetail.checkpoints.takeFailed", { message: savepoint.error.message })}
          </span>
        ) : null}
      </div>
    </form>
  );
}

/** Each task's part of a checkpoint: how long its snapshot, upload and alignment took, and its states. */
function CheckpointTasks({ checkpoint }: { checkpoint: RunCheckpoint }) {
  const t = useT();
  const language = usePreferences((s) => s.language);
  return (
    <Card title={t("runDetail.checkpoints.tasksOf", { id: checkpoint.id ?? 0 })}>
      {checkpoint.error ? (
        <p className="mb-3 text-sm">
          <span className="font-mono text-xs text-danger">{checkpoint.error.code}</span> {checkpoint.error.message}
        </p>
      ) : null}
      <DescriptionList
        items={[
          {
            label: t("runDetail.checkpoints.path"),
            value: <span className="break-all font-mono text-xs">{checkpoint.path}</span>,
          },
          { label: t("runDetail.checkpoints.reason"), value: checkpoint.reason },
          { label: t("runDetail.checkpoints.attempt"), value: checkpoint.attempt },
        ]}
      />
      <Table
        className="mt-3"
        caption={t("runDetail.checkpoints.tasksOf", { id: checkpoint.id ?? 0 })}
        rows={checkpoint.tasks ?? []}
        rowKey={(task, index) => task.task ?? String(index)}
        empty={t("runDetail.checkpoints.noTasks")}
        columns={[
          {
            key: "task",
            header: t("runDetail.checkpoints.task"),
            cell: (task) => <span className="break-all font-mono text-xs">{task.task}</span>,
          },
          {
            key: "sync",
            header: t("runDetail.checkpoints.sync"),
            cell: (task) => formatDuration(task.syncMs),
            className: "text-right tabular-nums",
          },
          {
            key: "async",
            header: t("runDetail.checkpoints.async"),
            cell: (task) => formatDuration(task.asyncMs),
            className: "text-right tabular-nums",
          },
          {
            key: "alignment",
            header: t("runDetail.checkpoints.alignment"),
            cell: (task) => formatDuration(task.alignmentMs),
            className: "text-right tabular-nums",
          },
          {
            key: "size",
            header: t("runDetail.checkpoints.stateSize"),
            cell: (task) => formatBytes(task.stateBytes, language),
            className: "text-right tabular-nums",
          },
        ]}
      />
    </Card>
  );
}

/**
 * The checkpoints and savepoints of a run, newest first, with each task's part of the one chosen;
 * a running run takes a savepoint from here. The chosen checkpoint is in the address.
 */
export function CheckpointsTab({
  id,
  live,
  running,
  selected,
  onSelect,
}: {
  id: string;
  live: boolean;
  running: boolean;
  selected: string | null;
  onSelect: (checkpoint: string) => void;
}) {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const checkpoints = useRunCheckpoints(id, live);
  if (checkpoints.isPending) return <LoadingPanel className="min-h-32" />;
  if (checkpoints.isError) {
    return (
      <Card>
        <p className="text-sm text-danger">
          {t("runDetail.checkpoints.failed", { message: checkpoints.error.message })}
        </p>
      </Card>
    );
  }
  const list = checkpoints.data ?? [];
  const summary = checkpointSummary(list);
  const chosen = list.find((c) => String(c.id) === selected) ?? list.find((c) => c.status === "COMPLETED") ?? list[0];
  return (
    <div className="space-y-4">
      <div className="grid items-start gap-4 lg:grid-cols-2">
        <Card title={t("runDetail.checkpoints.summary")}>
          <DescriptionList
            items={[
              {
                label: t("runDetail.checkpoints.last"),
                value: summary.last
                  ? t("runDetail.checkpoints.lastValue", {
                      id: summary.last.id ?? 0,
                      at: formatTime(summary.last.completedAt, language),
                    })
                  : "–",
              },
              { label: t("runDetail.checkpoints.completed"), value: summary.completed },
              { label: t("runDetail.checkpoints.savepoints"), value: summary.savepoints },
              {
                label: t("runDetail.checkpoints.failedCount"),
                value: summary.failed > 0 ? <span className="text-danger">{summary.failed}</span> : 0,
              },
            ]}
          />
        </Card>
        <Card title={t("runDetail.checkpoints.savepoint")}>
          {running ? (
            <SavepointForm id={id} />
          ) : (
            <p className="text-sm text-fg-muted">{t("runDetail.checkpoints.notRunning")}</p>
          )}
        </Card>
      </div>
      {list.length === 0 ? (
        <Card>
          <Empty
            icon="history"
            title={t("runDetail.checkpoints.none")}
            description={t("runDetail.checkpoints.noneHint")}
          />
        </Card>
      ) : (
        <>
          <Card>
            <Table
              caption={t("runDetail.checkpoints.caption")}
              rows={list}
              rowKey={(checkpoint, index) => String(checkpoint.id ?? index)}
              columns={[
                {
                  key: "id",
                  header: t("runDetail.checkpoints.id"),
                  cell: (checkpoint) => (
                    <Button
                      size="sm"
                      variant={checkpoint === chosen ? "secondary" : "ghost"}
                      aria-pressed={checkpoint === chosen}
                      onClick={() => onSelect(String(checkpoint.id))}
                      className="tabular-nums"
                    >
                      #{checkpoint.id}
                    </Button>
                  ),
                },
                {
                  key: "type",
                  header: t("runDetail.checkpoints.type"),
                  cell: (checkpoint) => (
                    <Badge tone={checkpoint.type === "SAVEPOINT" ? "info" : "neutral"}>
                      {t(
                        checkpoint.type === "SAVEPOINT"
                          ? "runDetail.checkpoints.types.SAVEPOINT"
                          : "runDetail.checkpoints.types.CHECKPOINT",
                      )}
                    </Badge>
                  ),
                },
                {
                  key: "status",
                  header: t("runDetail.checkpoints.status"),
                  cell: (checkpoint) => (
                    <Badge tone={STATUS_TONES[checkpoint.status ?? ""] ?? "neutral"}>
                      {t(
                        checkpoint.status === "COMPLETED"
                          ? "runDetail.checkpoints.states.COMPLETED"
                          : checkpoint.status === "FAILED"
                            ? "runDetail.checkpoints.states.FAILED"
                            : "runDetail.checkpoints.states.IN_PROGRESS",
                      )}
                    </Badge>
                  ),
                },
                { key: "reason", header: t("runDetail.checkpoints.reason"), cell: (checkpoint) => checkpoint.reason },
                {
                  key: "taken",
                  header: t("runDetail.checkpoints.takenAt"),
                  cell: (checkpoint) => formatTime(checkpoint.triggeredAt, language),
                  className: "whitespace-nowrap",
                },
                {
                  key: "duration",
                  header: t("runDetail.checkpoints.duration"),
                  cell: (checkpoint) => formatDuration(checkpointDuration(checkpoint)),
                  className: "whitespace-nowrap text-right tabular-nums",
                },
                {
                  key: "alignment",
                  header: t("runDetail.checkpoints.alignment"),
                  cell: (checkpoint) => formatDuration(longestAlignment(checkpoint)),
                  className: "whitespace-nowrap text-right tabular-nums",
                },
                {
                  key: "size",
                  header: t("runDetail.checkpoints.size"),
                  cell: (checkpoint) => formatBytes(checkpoint.sizeBytes, language),
                  className: "whitespace-nowrap text-right tabular-nums",
                },
                {
                  key: "detail",
                  header: t("runDetail.checkpoints.detail"),
                  className: "max-w-80",
                  cell: (checkpoint) =>
                    checkpoint.error ? (
                      // the whole error is in the detail of the checkpoint below
                      <span className="line-clamp-2 text-danger">
                        <span className="font-mono text-xs">{checkpoint.error.code}</span> {checkpoint.error.message}
                      </span>
                    ) : (
                      (checkpoint.name ?? "")
                    ),
                },
              ]}
            />
          </Card>
          {chosen ? <CheckpointTasks checkpoint={chosen} /> : null}
        </>
      )}
    </div>
  );
}
