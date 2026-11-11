/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";

/** Label and value pairs. */
export function DescriptionList({ items }: { items: { label: ReactNode; value: ReactNode }[] }) {
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
      {items.map((item, index) => (
        <div key={index} className="contents">
          <dt className="text-fg-muted">{item.label}</dt>
          <dd className="min-w-0 break-words text-fg">{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}
