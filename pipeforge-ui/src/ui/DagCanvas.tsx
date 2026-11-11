/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent, type WheelEvent } from "react";
import { edgePath, layout, NODE_HEIGHT, NODE_WIDTH } from "@/canvas/layout";
import { cx } from "./cx";
import { Icon, type IconName } from "./Icon";
import { IconButton } from "./IconButton";

export interface DagNode {
  id: string;
  kind: "source" | "transform" | "sink";
  /** The second line, e.g. the component type. */
  detail: string;
  inputs: string[];
  status?: "ok" | "error" | "warning";
  /** The accessible name of the node button. */
  label: string;
}

const KIND_ICONS: Record<DagNode["kind"], IconName> = { source: "source", transform: "transform", sink: "sink" };
const MIN_SCALE = 0.3;
const MAX_SCALE = 2;
const PADDING = 24;
/** Room above the graph for the toolbar. */
const TOP = 56;

/**
 * A read-only picture of a job graph: nodes in layers from left to right with curved edges. Drag
 * the background or use the wheel to pan, Ctrl or Cmd with the wheel to zoom; the toolbar zooms and
 * fits the graph into view, as happens whenever the graph or the size of the area changes. Nodes are buttons: choosing one calls `onSelect`.
 */
export function DagCanvas({
  nodes,
  label,
  labels,
  onSelect,
  className,
}: {
  nodes: DagNode[];
  label: string;
  labels: { zoomIn: string; zoomOut: string; fit: string };
  onSelect?: (id: string) => void;
  className?: string;
}) {
  const { placed, width, height } = useMemo(() => layout(nodes), [nodes]);
  const positions = useMemo(() => new Map(placed.map((p) => [p.id, p])), [placed]);
  const [view, setView] = useState({ scale: 1, x: PADDING, y: TOP });
  const drag = useRef<{ x: number; y: number } | null>(null);
  const box = useRef<HTMLDivElement>(null);

  /** Scales the graph down (never up) to fit the visible area and centers it horizontally. */
  const fit = useCallback(() => {
    const area = box.current?.getBoundingClientRect();
    if (!area || area.width === 0 || width === 0) {
      setView({ scale: 1, x: PADDING, y: TOP });
      return;
    }
    const scale = Math.max(
      MIN_SCALE,
      Math.min(1, (area.width - 2 * PADDING) / width, (area.height - TOP - PADDING) / height),
    );
    setView({ scale, x: Math.max(PADDING, (area.width - width * scale) / 2), y: TOP });
  }, [width, height]);

  // Fit when the graph changes and when the area is resized (window, splitter).
  useEffect(() => {
    fit();
    const element = box.current;
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => fit());
    observer.observe(element);
    return () => observer.disconnect();
  }, [fit]);

  const zoom = (factor: number) =>
    setView((v) => ({ ...v, scale: Math.min(MAX_SCALE, Math.max(MIN_SCALE, v.scale * factor)) }));

  const onWheel = (e: WheelEvent<HTMLDivElement>) => {
    if (e.ctrlKey || e.metaKey) {
      zoom(e.deltaY < 0 ? 1.1 : 1 / 1.1);
    } else {
      setView((v) => ({ ...v, x: v.x - e.deltaX, y: v.y - e.deltaY }));
    }
  };
  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.target !== e.currentTarget) return;
    drag.current = { x: e.clientX, y: e.clientY };
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    const start = drag.current;
    if (!start) return;
    drag.current = { x: e.clientX, y: e.clientY };
    setView((v) => ({ ...v, x: v.x + e.clientX - start.x, y: v.y + e.clientY - start.y }));
  };

  return (
    <div ref={box} className={cx("relative h-full min-h-48 overflow-hidden bg-canvas", className)}>
      <div
        role="group"
        aria-label={label}
        className="absolute inset-0 cursor-grab touch-none active:cursor-grabbing"
        onWheel={onWheel}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={() => (drag.current = null)}
      >
        <div
          className="pointer-events-none absolute left-0 top-0 origin-top-left"
          style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})`, width, height }}
        >
          <svg width={width} height={height} className="absolute inset-0 overflow-visible" aria-hidden="true">
            {nodes.flatMap((node) =>
              node.inputs.map((input) => {
                const from = positions.get(input);
                const to = positions.get(node.id);
                return from && to ? (
                  <path
                    key={`${input}->${node.id}`}
                    d={edgePath(from, to)}
                    fill="none"
                    stroke="var(--pf-border-strong)"
                    strokeWidth={1.5}
                  />
                ) : null;
              }),
            )}
          </svg>
          {nodes.map((node) => {
            const p = positions.get(node.id);
            if (!p) return null;
            return (
              <button
                key={node.id}
                type="button"
                aria-label={node.label}
                onClick={() => onSelect?.(node.id)}
                className={cx(
                  "pointer-events-auto absolute flex items-center gap-2 rounded-card border bg-surface px-3 text-left shadow-card",
                  "transition-colors duration-fast ease-out hover:border-primary",
                  node.status === "error"
                    ? "border-danger"
                    : node.status === "warning"
                      ? "border-warning"
                      : "border-border",
                )}
                style={{ left: p.x, top: p.y, width: NODE_WIDTH, height: NODE_HEIGHT }}
              >
                <span className="flex size-7 shrink-0 items-center justify-center rounded-control bg-surface-2 text-fg-muted">
                  <Icon name={KIND_ICONS[node.kind]} />
                </span>
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium text-fg">{node.id}</span>
                  <span className="block truncate text-xs text-fg-muted">{node.detail}</span>
                </span>
                {node.status === "error" ? <Icon name="alert" className="ml-auto shrink-0 text-danger" /> : null}
              </button>
            );
          })}
        </div>
      </div>
      <div className="absolute right-2 top-2 flex gap-1 rounded-control border border-border bg-surface p-0.5 shadow-card">
        <IconButton size="sm" icon="zoomIn" label={labels.zoomIn} onClick={() => zoom(1.2)} />
        <IconButton size="sm" icon="zoomOut" label={labels.zoomOut} onClick={() => zoom(1 / 1.2)} />
        <IconButton size="sm" icon="fit" label={labels.fit} onClick={fit} />
      </div>
    </div>
  );
}
