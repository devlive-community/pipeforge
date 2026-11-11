/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";
import { cx } from "./cx";

export type BadgeTone = "neutral" | "primary" | "success" | "warning" | "danger" | "info";

const tones: Record<BadgeTone, { badge: string; dot: string }> = {
  neutral: { badge: "bg-surface-3 text-fg", dot: "bg-fg-subtle" },
  primary: { badge: "bg-primary-soft text-fg", dot: "bg-primary" },
  success: { badge: "bg-success-soft text-fg", dot: "bg-success" },
  warning: { badge: "bg-warning-soft text-fg", dot: "bg-warning" },
  danger: { badge: "bg-danger-soft text-fg", dot: "bg-danger" },
  info: { badge: "bg-info-soft text-fg", dot: "bg-info" },
};

/** A short status label; the colored dot repeats the tone so it does not rely on color alone. */
export function Badge({ tone = "neutral", children }: { tone?: BadgeTone; children: ReactNode }) {
  return (
    <span
      className={cx(
        "inline-flex items-center gap-1.5 whitespace-nowrap rounded-control px-2 py-0.5 text-xs font-medium",
        tones[tone].badge,
      )}
    >
      <span aria-hidden="true" className={cx("size-1.5 rounded-full", tones[tone].dot)} />
      {children}
    </span>
  );
}
