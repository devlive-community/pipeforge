/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";

export function LoadingIndicator({ label, size }: { label: ReactNode; size: number }) {
  return (
    <div role="status" className="flex flex-col items-center gap-2 text-sm text-fg-muted">
      <img src={`${process.env.NEXT_PUBLIC_BASE_PATH ?? ""}/pipeforge-loading.svg`} alt="" width={size} height={size} />
      <div>{label}</div>
    </div>
  );
}
