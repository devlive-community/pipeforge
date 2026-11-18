/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useRef, type RefObject } from "react";

export function ResizeHandle({
  label,
  direction,
  container,
  value,
  minimum,
  maximum,
  onResize,
  onReset,
}: {
  label: string;
  direction: "right" | "left" | "up";
  container: RefObject<HTMLElement | null>;
  value: number;
  minimum: number;
  maximum: number;
  onResize: (value: number) => void;
  onReset: () => void;
}) {
  const drag = useRef<{ pointer: number; start: number; value: number; extent: number } | null>(null);
  const horizontal = direction === "up";
  const grow = direction === "right" ? 1 : -1;
  const resize = (next: number) => onResize(Math.min(maximum, Math.max(minimum, next)));

  return (
    <div
      role="separator"
      tabIndex={0}
      aria-label={label}
      aria-orientation={horizontal ? "horizontal" : "vertical"}
      aria-valuenow={Math.round(value)}
      aria-valuemin={minimum}
      aria-valuemax={maximum}
      className={horizontal ? "editor-resize editor-resize-dock" : "editor-resize editor-resize-side"}
      onPointerDown={(event) => {
        if (event.button !== 0) return;
        const extent = horizontal ? container.current?.clientHeight : container.current?.clientWidth;
        if (!extent) return;
        drag.current = {
          pointer: event.pointerId,
          start: horizontal ? event.clientY : event.clientX,
          value,
          extent,
        };
        event.currentTarget.setPointerCapture(event.pointerId);
      }}
      onPointerMove={(event) => {
        const current = drag.current;
        if (!current || current.pointer !== event.pointerId) return;
        const delta = (horizontal ? event.clientY : event.clientX) - current.start;
        if (delta !== 0) resize(current.value + (grow * delta * 100) / current.extent);
      }}
      onLostPointerCapture={() => {
        drag.current = null;
      }}
      onDoubleClick={onReset}
      onKeyDown={(event) => {
        const decreasing = horizontal ? "ArrowUp" : "ArrowLeft";
        const increasing = horizontal ? "ArrowDown" : "ArrowRight";
        if (event.key === "Home") resize(minimum);
        else if (event.key === "End") resize(maximum);
        else if (event.key === decreasing) resize(value - 2 * grow);
        else if (event.key === increasing) resize(value + 2 * grow);
        else return;
        event.preventDefault();
      }}
    />
  );
}
