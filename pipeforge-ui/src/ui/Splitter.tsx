/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";
import { cx } from "./cx";

const MIN = 20;
const MAX = 80;
const STEP = 5;

/**
 * Two panes side by side with a draggable divider. The divider is a focusable separator: the arrow
 * keys move it in steps of 5%, Home and End to the limits (20% to 80%).
 */
export function Splitter({
  first,
  second,
  label,
  initial = 50,
  className,
}: {
  first: ReactNode;
  second: ReactNode;
  label: string;
  initial?: number;
  className?: string;
}) {
  const [size, setSize] = useState(initial);
  const container = useRef<HTMLDivElement>(null);
  const clamp = (value: number) => Math.min(MAX, Math.max(MIN, value));

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    if (!e.currentTarget.hasPointerCapture(e.pointerId) || !container.current) return;
    const box = container.current.getBoundingClientRect();
    if (box.width > 0) setSize(clamp(((e.clientX - box.left) / box.width) * 100));
  };
  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const moves: Record<string, number> = { ArrowLeft: size - STEP, ArrowRight: size + STEP, Home: MIN, End: MAX };
    if (e.key in moves) {
      e.preventDefault();
      setSize(clamp(moves[e.key] as number));
    }
  };

  return (
    <div ref={container} className={cx("flex min-h-0 min-w-0", className)}>
      <div className="min-w-0" style={{ flexBasis: `${size}%` }}>
        {first}
      </div>
      <div
        role="separator"
        aria-orientation="vertical"
        aria-label={label}
        aria-valuemin={MIN}
        aria-valuemax={MAX}
        aria-valuenow={Math.round(size)}
        tabIndex={0}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onKeyDown={onKey}
        className="w-1.5 shrink-0 cursor-col-resize bg-border transition-colors duration-fast ease-out hover:bg-primary focus-visible:bg-primary"
      />
      <div className="min-w-0 flex-1">{second}</div>
    </div>
  );
}
