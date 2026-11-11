/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";
import { Icon, type IconName } from "./Icon";

/** A placeholder for a page or area without content: no data, an error, a missing page. */
export function Empty({
  icon = "compass",
  title,
  description,
  action,
}: {
  icon?: IconName;
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center px-6 py-12 text-center">
      <span className="flex size-12 items-center justify-center rounded-full bg-surface-2 text-fg-muted">
        <Icon name={icon} size={22} />
      </span>
      <h2 className="mt-4 text-base font-semibold text-fg">{title}</h2>
      {description ? <p className="mt-1 max-w-md text-sm text-fg-muted">{description}</p> : null}
      {action ? <div className="mt-5">{action}</div> : null}
    </div>
  );
}
