/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";
import { t, type Lang } from "@/lib/i18n";
import { cx } from "../ui/cx";
import { Icon } from "../ui/Icon";

export type CalloutType = "note" | "tip" | "warning" | "danger";

const styles: Record<CalloutType, { box: string; icon: string; name: "info" | "alert" }> = {
  note: { box: "border-info bg-info-soft", icon: "text-info", name: "info" },
  tip: { box: "border-success bg-success-soft", icon: "text-success", name: "info" },
  warning: { box: "border-warning bg-warning-soft", icon: "text-warning", name: "alert" },
  danger: { box: "border-danger bg-danger-soft", icon: "text-danger", name: "alert" },
};

/** Highlighted note, tip, warning or danger box. */
export function Callout({
  type = "note",
  title,
  lang = "en-US",
  children,
}: {
  type?: CalloutType;
  title?: string;
  lang?: Lang;
  children: ReactNode;
}) {
  const style = styles[type] ?? styles.note;
  return (
    <aside role="note" className={cx("flex gap-3 rounded-popover border-l-4 px-4 py-3", style.box)}>
      <Icon name={style.name} size={18} className={cx("mt-1 shrink-0", style.icon)} />
      <div className="min-w-0 [&>*+*]:mt-2">
        <p className="font-semibold text-fg">{title ?? t(lang).callout[type]}</p>
        <div className="text-fg [&>*+*]:mt-2">{children}</div>
      </div>
    </aside>
  );
}
