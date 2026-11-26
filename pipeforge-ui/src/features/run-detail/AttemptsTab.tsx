/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { RunAttempt, RunState } from "@pipeforge/client";
import { useEffect, useState } from "react";
import { useRunAttempts } from "@/api/queries";
import { formatCount, formatDuration, formatTime } from "@/features/runs/format";
import { RunStateBadge } from "@/features/runs/RunsPage";
import { useT } from "@/i18n";
import { usePreferences } from "@/state/preferences";
import { Card, Empty, Icon } from "@/ui";
import { LoadingPanel } from "@/ui/LoadingPanel";

/** How long until `at`, never below zero; null without a time. */
export function timeUntil(at: string | null | undefined, now: number): number | null {
  return at ? Math.max(0, Date.parse(at) - now) : null;
}

/** How long an attempt ran: what its metrics say, else from its start to its end. */
export function attemptDuration(attempt: RunAttempt): number | null {
  if (attempt.elapsedMs !== undefined && attempt.elapsedMs !== null) return attempt.elapsedMs;
  if (!attempt.startedAt || !attempt.endedAt) return null;
  return Date.parse(attempt.endedAt) - Date.parse(attempt.startedAt);
}

/** The clock, once a second while `ticking`. */
function useNow(ticking: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!ticking) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [ticking]);
  return now;
}

/** The attempts of a run, newest first: when each started and ended, why it ended and what it did. */
export function AttemptsTab({ id, live }: { id: string; live: boolean }) {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const attempts = useRunAttempts(id, live);
  const list = attempts.data ?? [];
  const waiting = list.find((attempt) => attempt.retryAt);
  const now = useNow(Boolean(waiting));
  if (attempts.isPending) return <LoadingPanel className="min-h-32" />;
  if (attempts.isError) {
    return (
      <Card>
        <p className="text-sm text-danger">{t("runDetail.attempts.failed", { message: attempts.error.message })}</p>
      </Card>
    );
  }
  if (list.length === 0) {
    return (
      <Card>
        <Empty icon="history" title={t("runDetail.attempts.none")} />
      </Card>
    );
  }
  const left = timeUntil(waiting?.retryAt, now);
  return (
    <div className="space-y-4">
      {waiting ? (
        <Card>
          <div role="timer" aria-live="off" className="flex items-center gap-3 text-sm">
            <Icon name="refresh" className="text-warning" />
            <span className="text-fg">
              {left === 0
                ? t("runDetail.attempts.startingNow", { attempt: (waiting.attempt ?? 0) + 1 })
                : t("runDetail.attempts.nextIn", {
                    attempt: (waiting.attempt ?? 0) + 1,
                    left: formatDuration(left),
                    at: formatTime(waiting.retryAt, language),
                  })}
            </span>
          </div>
        </Card>
      ) : null}
      <ol aria-label={t("runDetail.attempts.caption")} className="space-y-3">
        {[...list].reverse().map((attempt) => (
          <li key={attempt.attempt}>
            <Card>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <h3 className="text-sm font-semibold text-fg">
                  {t("runDetail.attempts.title", { attempt: attempt.attempt ?? 0 })}
                </h3>
                <RunStateBadge state={attempt.status as RunState} />
                <span className="text-sm text-fg-muted">
                  {formatTime(attempt.startedAt, language)}
                  {attempt.endedAt ? ` → ${formatTime(attempt.endedAt, language)}` : ""}
                </span>
              </div>
              <dl className="mt-3 grid grid-cols-2 gap-x-6 gap-y-2 text-sm sm:grid-cols-5">
                {[
                  [t("runDetail.attempts.duration"), formatDuration(attemptDuration(attempt))],
                  [t("runDetail.attempts.read"), formatCount(attempt.rowsRead, language)],
                  [t("runDetail.attempts.written"), formatCount(attempt.rowsWritten, language)],
                  [t("runDetail.attempts.dirty"), formatCount(attempt.rowsDirty, language)],
                ].map(([label, value]) => (
                  <div key={label}>
                    <dt className="text-xs text-fg-muted">{label}</dt>
                    <dd className="tabular-nums text-fg">{value}</dd>
                  </div>
                ))}
                <div className="col-span-2 sm:col-span-1">
                  <dt className="text-xs text-fg-muted">{t("runDetail.attempts.from")}</dt>
                  {attempt.restorePath ? (
                    <dd className="break-all font-mono text-xs text-fg">{attempt.restorePath}</dd>
                  ) : (
                    <dd className="text-fg">{t("runDetail.attempts.fromStart")}</dd>
                  )}
                </div>
              </dl>
              {attempt.error ? (
                <p className="mt-3 text-sm text-danger">
                  <span className="font-mono text-xs">{attempt.error.code}</span> {attempt.error.message}
                </p>
              ) : null}
            </Card>
          </li>
        ))}
      </ol>
    </div>
  );
}
