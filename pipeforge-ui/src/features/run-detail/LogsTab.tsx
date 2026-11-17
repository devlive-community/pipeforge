/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { RunTask } from "@pipeforge/client";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useRunTasks } from "@/api/queries";
import { useT } from "@/i18n";
import { Button, Card, DropdownMenu, Empty, Input, SegmentedControl } from "@/ui";
import { cx } from "@/ui/cx";
import { LoadingPanel } from "@/ui/LoadingPanel";
import {
  logLines,
  marked,
  parseTaskKey,
  readWholeLog,
  saveText,
  taskKey,
  useTaskLog,
  type LogLevel,
  type TaskRef,
} from "./logs";

const LEVEL_CLASSES: Record<LogLevel, string> = {
  TRACE: "text-fg-subtle",
  DEBUG: "text-fg-subtle",
  INFO: "text-fg",
  WARN: "text-warning",
  ERROR: "text-danger",
};

const LEVEL_FILTERS = ["all", "DEBUG", "INFO", "WARN", "ERROR"] as const;

function ref(task: RunTask): TaskRef {
  return { vertex: task.vertex ?? "", subtask: task.subtask ?? 0, attempt: task.attempt ?? 0 };
}

/**
 * The log of one task of a run, from the worker it ran on: chosen by task, filtered by level and
 * text on the server, followed while the task runs and scrolled along while the view is at its
 * end. The whole log can be downloaded.
 */
export function LogsTab({
  id,
  job,
  live,
  selected,
  onSelect,
}: {
  id: string;
  job: string;
  live: boolean;
  selected: string | null;
  onSelect: (task: string) => void;
}) {
  const t = useT();
  const tasks = useRunTasks(id, live);
  const list = tasks.data ?? [];
  const task = parseTaskKey(selected) ?? (list[0] ? ref(list[0]) : null);
  const [term, setTerm] = useState("");
  const [contains, setContains] = useState("");
  const [level, setLevel] = useState<(typeof LEVEL_FILTERS)[number]>("all");
  const [follow, setFollow] = useState(true);
  const [downloading, setDownloading] = useState(false);
  const view = useRef<HTMLDivElement>(null);

  // ask the server once typing pauses
  useEffect(() => {
    const timer = setTimeout(() => setContains(term), 300);
    return () => clearTimeout(timer);
  }, [term]);

  const log = useTaskLog(id, task, contains, level === "all" ? undefined : level);
  const lines = logLines(log.text);

  useLayoutEffect(() => {
    const element = view.current;
    if (follow && element) element.scrollTop = element.scrollHeight;
  }, [follow, log.text]);

  if (tasks.isPending) return <LoadingPanel className="min-h-32" />;
  if (tasks.isError) {
    return (
      <Card>
        <p className="text-sm text-danger">{t("runDetail.logs.tasksFailed", { message: tasks.error.message })}</p>
      </Card>
    );
  }
  if (!task) {
    return (
      <Card>
        <Empty icon="code" title={t("runDetail.logs.noTasks")} />
      </Card>
    );
  }

  const describe = (item: RunTask) =>
    t((item.attempt ?? 0) > 0 ? "runDetail.logs.taskAttempt" : "runDetail.logs.task", {
      vertex: item.vertex ?? "",
      subtask: item.subtask ?? 0,
      attempt: item.attempt ?? 0,
      worker: item.worker ?? "–",
      state: item.state ?? "",
    });
  const current = list.find((item) => taskKey(ref(item)) === taskKey(task));
  const download = async () => {
    setDownloading(true);
    try {
      const text = await readWholeLog(id, task);
      saveText(`${job}-${task.vertex}-${task.subtask}-${task.attempt}.log`, text);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <Card>
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <DropdownMenu
          label={t("runDetail.logs.taskLabel")}
          align="start"
          value={taskKey(task)}
          onSelect={onSelect}
          items={list.map((item) => ({ id: taskKey(ref(item)), label: describe(item) }))}
          trigger={(props) => (
            <Button {...props} size="sm" icon="chevronDown" aria-label={t("runDetail.logs.taskLabel")}>
              <span className="font-mono">{current ? describe(current) : `${task.vertex} #${task.subtask}`}</span>
            </Button>
          )}
        />
        <SegmentedControl
          label={t("runDetail.logs.level")}
          value={level}
          onChange={(value) => setLevel(value as (typeof LEVEL_FILTERS)[number])}
          segments={LEVEL_FILTERS.map((value) => ({ id: value, label: t(`runDetail.logs.levels.${value}`) }))}
        />
        <Input
          label={t("runDetail.logs.search")}
          hideLabel
          icon="search"
          placeholder={t("runDetail.logs.search")}
          value={term}
          onChange={(e) => setTerm(e.target.value)}
          className="w-56"
        />
        <div className="ml-auto flex gap-2">
          <Button
            size="sm"
            variant={follow ? "primary" : "secondary"}
            aria-pressed={follow}
            onClick={() => setFollow(!follow)}
          >
            {t("runDetail.logs.follow")}
          </Button>
          <Button size="sm" icon="download" loading={downloading} onClick={() => void download()}>
            {t("runDetail.logs.download")}
          </Button>
        </div>
      </div>
      {log.error ? (
        <p className="mb-2 text-sm text-danger">{t("runDetail.logs.failed", { message: log.error.message })}</p>
      ) : null}
      <div
        ref={view}
        role="log"
        aria-live="off"
        aria-label={t("runDetail.logs.view")}
        tabIndex={0}
        onScroll={(e) => {
          const element = e.currentTarget;
          setFollow(element.scrollHeight - element.scrollTop - element.clientHeight < 8);
        }}
        className="h-96 overflow-auto rounded-control bg-code-bg p-3 font-mono text-xs leading-5 text-code-fg"
      >
        {log.loading ? (
          <LoadingPanel className="min-h-24" />
        ) : lines.length === 0 ? (
          <p className="text-fg-muted">
            {contains || level !== "all" ? t("runDetail.logs.noMatch") : t("runDetail.logs.empty")}
          </p>
        ) : (
          lines.map((line, index) => (
            <div key={index} className={cx("whitespace-pre-wrap break-all", line.level && LEVEL_CLASSES[line.level])}>
              {marked(line.text, contains).map((piece, at) =>
                piece.match ? (
                  <mark key={at} className="rounded-sm bg-warning-soft text-fg">
                    {piece.text}
                  </mark>
                ) : (
                  piece.text
                ),
              )}
            </div>
          ))
        )}
      </div>
      <p className="mt-2 text-xs text-fg-subtle">
        {log.eof ? t("runDetail.logs.ended") : t("runDetail.logs.following")}
      </p>
    </Card>
  );
}
