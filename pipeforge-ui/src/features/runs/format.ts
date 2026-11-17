/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Language } from "@/i18n";

/** A count of rows with the separators of the language, or a dash when it is not known. */
export function formatCount(value: number | null | undefined, language: Language): string {
  return value === null || value === undefined ? "–" : new Intl.NumberFormat(language).format(value);
}

/** A moment as date and time of the browser's time zone. */
export function formatTime(iso: string | null | undefined, language: Language): string {
  if (!iso) return "–";
  return new Intl.DateTimeFormat(language, { dateStyle: "medium", timeStyle: "medium" }).format(new Date(iso));
}

/** A length of time, the two largest units: 850 ms, 12 s, 3 m 05 s, 2 h 07 m. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || ms < 0) return "–";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} m ${String(seconds % 60).padStart(2, "0")} s`;
  const hours = Math.floor(minutes / 60);
  return `${hours} h ${String(minutes % 60).padStart(2, "0")} m`;
}

/** How long a run took, or has been going: from its start to its end, or to `now` while it runs. */
export function runDuration(
  run: { startedAt?: string | null; endedAt?: string | null },
  now: number = Date.now(),
): number | null {
  if (!run.startedAt) return null;
  const end = run.endedAt ? Date.parse(run.endedAt) : now;
  return end - Date.parse(run.startedAt);
}
