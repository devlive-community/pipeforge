/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { cx } from "./cx";

/** A placeholder block shown while content loads; pulses gently unless motion is reduced. */
export function Skeleton({ className }: { className?: string }) {
  return <span aria-hidden="true" className={cx("block rounded-control bg-surface-3 animate-pulse-soft", className)} />;
}
