/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";
import { cx } from "./cx";

/** A bordered surface for a group of content, with an optional title, description and actions. */
export function Card({
  title,
  description,
  actions,
  className,
  children,
}: {
  title?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
  children?: ReactNode;
}) {
  return (
    <section className={cx("rounded-card border border-border bg-surface p-4 shadow-card", className)}>
      {title || actions ? (
        <div className="mb-3 flex items-start justify-between gap-3">
          <div>
            {title ? <h2 className="text-sm font-semibold text-fg">{title}</h2> : null}
            {description ? <p className="mt-0.5 text-sm text-fg-muted">{description}</p> : null}
          </div>
          {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
        </div>
      ) : null}
      {children}
    </section>
  );
}
