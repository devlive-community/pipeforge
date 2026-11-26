/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Run, RunState } from "@pipeforge/client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/api/client";
import { useRunCheckpoints } from "@/api/queries";
import { useT } from "@/i18n";
import { Button, ConfirmDialog, Input, SegmentedControl } from "@/ui";

/** What can be done to a run. */
export type RunAction = "pause" | "resume" | "savepoint" | "cancel" | "retry";

const ENDED_WITHOUT_SUCCESS: RunState[] = ["FAILED", "CANCELLED", "TIMEOUT"];

/** The actions a run in `state` offers, in the order they are shown. */
export function actionsOf(state: RunState): RunAction[] {
  switch (state) {
    case "RUNNING":
      return ["pause", "savepoint", "cancel"];
    case "PAUSED":
      return ["resume", "cancel"];
    case "QUEUE":
      return ["cancel"];
    default:
      return ENDED_WITHOUT_SUCCESS.includes(state) ? ["retry"] : [];
  }
}

/**
 * The buttons that pause, resume, cancel, retry or take a savepoint of a run, as its state allows;
 * each asks first in a dialog, which stays open with the error when the server refuses.
 */
export function RunControls({ run }: { run: Run }) {
  const t = useT();
  const id = run.id ?? "";
  const state = run.status as RunState;
  const actions = actionsOf(state);
  const [open, setOpen] = useState<RunAction | null>(null);
  const [name, setName] = useState("");
  const [reason, setReason] = useState("");
  const [from, setFrom] = useState<"CHECKPOINT" | "SCRATCH">("CHECKPOINT");
  const queries = useQueryClient();
  // a run that may be retried: what it can go on from is known before the dialog opens
  const checkpoints = useRunCheckpoints(id, false);
  const resumable = (checkpoints.data ?? []).some((c) => c.status === "COMPLETED");

  const action = useMutation<unknown, Error, RunAction>({
    mutationFn: (which) => {
      const named = name.trim() || undefined;
      switch (which) {
        case "pause":
          return api.pauseRun(id, named);
        case "resume":
          return api.resumeRun(id);
        case "savepoint":
          return api.savepointRun(id, named);
        case "cancel":
          return api.cancelRun(id, reason.trim() || undefined);
        case "retry":
          return api.retryRun(id, resumable ? from : "SCRATCH");
      }
    },
    onSuccess: () => {
      setOpen(null);
      void queries.invalidateQueries({ queryKey: ["run", id] });
      void queries.invalidateQueries({ queryKey: ["run-checkpoints", id] });
      void queries.invalidateQueries({ queryKey: ["runs"] });
    },
  });

  const show = (which: RunAction) => {
    action.reset();
    setName("");
    setReason("");
    setFrom(resumable ? "CHECKPOINT" : "SCRATCH");
    setOpen(which);
  };

  if (actions.length === 0) return null;
  const which = open ?? actions[0] ?? "cancel";
  const texts = {
    pause: { icon: "pause", tone: "primary" },
    resume: { icon: "play", tone: "primary" },
    savepoint: { icon: "bookmark", tone: "primary" },
    cancel: { icon: "stop", tone: "danger" },
    retry: { icon: "refresh", tone: "primary" },
  } as const;

  return (
    <>
      {actions.map((each) => (
        <Button
          key={each}
          size="sm"
          icon={texts[each].icon}
          variant={each === "cancel" ? "secondary" : "primary"}
          disabled={each === "retry" && checkpoints.isPending}
          onClick={() => show(each)}
        >
          {t(`runDetail.controls.${each}.button`)}
        </Button>
      ))}
      <ConfirmDialog
        open={open !== null}
        title={t(`runDetail.controls.${which}.title`, { job: run.job ?? "" })}
        description={t(`runDetail.controls.${which}.description`)}
        confirmLabel={t(`runDetail.controls.${which}.confirm`)}
        cancelLabel={t("runDetail.controls.keep")}
        busyLabel={t(`runDetail.controls.${which}.busy`)}
        tone={texts[which].tone}
        busy={action.isPending}
        error={action.isError ? t("runDetail.controls.failed", { message: action.error.message }) : null}
        onConfirm={() => action.mutate(which)}
        onClose={() => setOpen(null)}
      >
        {which === "pause" || which === "savepoint" ? (
          <Input
            label={t("runDetail.controls.name")}
            placeholder={t("runDetail.checkpoints.savepointPlaceholder")}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        ) : null}
        {which === "cancel" ? (
          <Input
            label={t("runDetail.controls.reason")}
            placeholder={t("runDetail.controls.reasonPlaceholder")}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        ) : null}
        {which === "retry" ? (
          resumable ? (
            <SegmentedControl
              label={t("runDetail.controls.retry.from")}
              value={from}
              onChange={(next) => setFrom(next as "CHECKPOINT" | "SCRATCH")}
              segments={[
                { id: "CHECKPOINT", label: t("runDetail.controls.retry.checkpoint") },
                { id: "SCRATCH", label: t("runDetail.controls.retry.scratch") },
              ]}
            />
          ) : (
            <p className="text-sm text-fg-muted">{t("runDetail.controls.retry.noCheckpoint")}</p>
          )
        ) : null}
      </ConfirmDialog>
    </>
  );
}
