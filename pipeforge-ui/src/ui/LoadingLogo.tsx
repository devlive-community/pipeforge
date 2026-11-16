/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { cx } from "./cx";

export function LoadingLogo({ size = 16, className }: { size?: number; className?: string }) {
  return <img className={cx("shrink-0", className)} src="/pipeforge-loading.svg" alt="" width={size} height={size} />;
}
