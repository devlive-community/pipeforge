/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useRef, type KeyboardEvent } from "react";
import { cx } from "./cx";

export interface Segment {
  id: string;
  label: string;
}

/**
 * One choice out of a few, shown side by side (a radio group). Keyboard: the arrow keys move and
 * choose; only the chosen segment is in the tab order.
 */
export function SegmentedControl({
  label,
  segments,
  value,
  onChange,
}: {
  label: string;
  segments: Segment[];
  value: string;
  onChange: (id: string) => void;
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const current = segments.findIndex((s) => s.id === value);
    const last = segments.length - 1;
    const moves: Record<string, number> = {
      ArrowRight: current === last ? 0 : current + 1,
      ArrowDown: current === last ? 0 : current + 1,
      ArrowLeft: current <= 0 ? last : current - 1,
      ArrowUp: current <= 0 ? last : current - 1,
    };
    const next = moves[e.key];
    if (next === undefined) return;
    e.preventDefault();
    const segment = segments[next];
    if (segment) {
      onChange(segment.id);
      refs.current[next]?.focus();
    }
  };
  return (
    <div
      role="radiogroup"
      aria-label={label}
      onKeyDown={onKey}
      className="inline-flex flex-wrap gap-0.5 rounded-control border border-border bg-surface-2 p-0.5"
    >
      {segments.map((segment, index) => {
        const checked = segment.id === value;
        return (
          <button
            key={segment.id}
            ref={(element) => {
              refs.current[index] = element;
            }}
            type="button"
            role="radio"
            aria-checked={checked}
            tabIndex={checked ? 0 : -1}
            onClick={() => onChange(segment.id)}
            className={cx(
              "h-control-sm whitespace-nowrap rounded-control px-2.5 text-sm transition-colors duration-fast ease-out",
              checked ? "bg-surface font-medium text-fg shadow-card" : "text-fg-muted hover:text-fg",
            )}
          >
            {segment.label}
          </button>
        );
      })}
    </div>
  );
}
