/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useT } from "@/i18n";
import { cx } from "./cx";
import { LoadingLogo } from "./LoadingLogo";

export function LoadingPanel({ className }: { className?: string }) {
  const t = useT();
  return (
    <div
      role="status"
      className={cx("flex min-h-36 flex-col items-center justify-center gap-2 text-sm text-fg-muted", className)}
    >
      <LoadingLogo size={56} />
      <span>{t("app.loading")}</span>
    </div>
  );
}
