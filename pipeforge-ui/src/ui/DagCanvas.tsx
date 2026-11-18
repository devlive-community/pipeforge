/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import {
  Fragment,
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type PointerEvent,
  type WheelEvent,
} from "react";
import { DEFAULT_NODE_SIZE, edgePath, layout } from "@/canvas/layout";
import { cx } from "./cx";
import { Icon, type IconName } from "./Icon";
import { IconButton } from "./IconButton";

export interface DagNode {
  id: string;
  kind: "source" | "transform" | "sink";
  /** The second line, e.g. the component type. */
  detail: string;
  kindLabel?: string;
  inputs: string[];
  status?: "ok" | "error" | "warning";
  /** The accessible name of the node button. */
  label: string;
  /** A short line under the node, such as its rows and rate. */
  metric?: string;
  /** Marks the node, such as the bottleneck of a run. */
  emphasis?: boolean;
  /** The node is the selected one. */
  selected?: boolean;
}

type Point = { x: number; y: number };
const PORT_DRAG = "application/x-pipeforge-output";

interface GraphEditing {
  componentDragType: string;
  onComponent: (data: string) => string | undefined;
  canConnect: (from: string, to: string) => boolean;
  onConnect: (from: string, to: string) => void;
  outputLabel: (id: string) => string;
  inputLabel: (id: string) => string;
}

const KIND_ICONS: Record<DagNode["kind"], IconName> = { source: "source", transform: "transform", sink: "sink" };
const EDITOR_NODE_SIZE = { width: 184, height: 82 };
const MIN_SCALE = 0.3;
const MAX_SCALE = 2;
const PADDING = 24;
/** Room above the graph for the toolbar. */
const TOP = 53;

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
  edgeWidth,
  className,
  editor = false,
  editing,
  empty,
}: {
  nodes: DagNode[];
  /** The width of the edge from one node to another, 1 to 8; 2 when not given. */
  edgeWidth?: (from: string, to: string) => number | undefined;
  label: string;
  labels: { zoomIn: string; zoomOut: string; fit: string };
  onSelect?: (id: string) => void;
  className?: string;
  editor?: boolean;
  editing?: GraphEditing;
  empty?: string;
}) {
  const arrowId = useId();
  const nodeSize = editor ? EDITOR_NODE_SIZE : DEFAULT_NODE_SIZE;
  const { placed, width, height } = useMemo(() => layout(nodes, nodeSize), [nodes, nodeSize]);
  const [positionsById, setPositionsById] = useState<Record<string, Point>>({});
  const positions = useMemo(
    () => new Map(placed.map((p) => [p.id, { ...p, ...positionsById[p.id] }])),
    [placed, positionsById],
  );
  const [connectFrom, setConnectFrom] = useState<string>();
  const nodeDrag = useRef<{ id: string; start: Point; position: Point } | null>(null);
  const movedNode = useRef(false);
  const [view, setView] = useState({ scale: 1, x: PADDING, y: TOP });
  const drag = useRef<{ x: number; y: number } | null>(null);
  const box = useRef<HTMLDivElement>(null);

  /** Scales the graph down (never up) to fit the visible area and centers it horizontally. */
  const fit = useCallback(
    (contentWidth = width, contentHeight = height) => {
      const area = box.current?.getBoundingClientRect();
      if (!area || area.width === 0 || contentWidth === 0) {
        setView({ scale: 1, x: PADDING, y: TOP });
        return;
      }
      const scale = Math.max(
        MIN_SCALE,
        Math.min(1, (area.width - 2 * PADDING) / contentWidth, (area.height - TOP - PADDING) / contentHeight),
      );
      const y = editor
        ? Math.max(PADDING, (area.height - contentHeight * scale) / 2 - 8)
        : Math.max(TOP, (area.height + TOP - contentHeight * scale) / 2 - PADDING);
      setView({ scale, x: Math.max(PADDING, (area.width - contentWidth * scale) / 2), y });
    },
    [editor, width, height],
  );

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

  function positionNode(id: string, x: number, y: number) {
    setPositionsById((current) => ({ ...current, [id]: { x: Math.max(0, x), y: Math.max(0, y) } }));
  }

  function connectTo(id: string, from = connectFrom) {
    if (from && editing?.canConnect(from, id)) editing.onConnect(from, id);
    setConnectFrom(undefined);
  }

  function fitVisible() {
    let contentWidth = width;
    let contentHeight = height;
    for (const position of positions.values()) {
      contentWidth = Math.max(contentWidth, position.x + nodeSize.width);
      contentHeight = Math.max(contentHeight, position.y + nodeSize.height);
    }
    fit(contentWidth, contentHeight);
  }

  return (
    <div ref={box} className={cx("dag-surface relative h-full overflow-hidden", !editing && "min-h-48", className)}>
      <div
        role="group"
        aria-label={label}
        className="absolute inset-0 cursor-grab touch-none active:cursor-grabbing"
        onWheel={onWheel}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onLostPointerCapture={() => (drag.current = null)}
        onKeyDown={(event) => {
          if (event.key === "Escape") setConnectFrom(undefined);
        }}
        onDragOver={(event) => {
          if (!editing || !event.dataTransfer.types.includes(editing.componentDragType)) return;
          event.preventDefault();
          event.dataTransfer.dropEffect = "copy";
        }}
        onDrop={(event) => {
          if (!editing) return;
          const data = event.dataTransfer.getData(editing.componentDragType);
          if (!data) return;
          event.preventDefault();
          const area = event.currentTarget.getBoundingClientRect();
          const id = editing.onComponent(data);
          if (id) {
            positionNode(
              id,
              (event.clientX - area.left - view.x) / view.scale - nodeSize.width / 2,
              (event.clientY - area.top - view.y) / view.scale - nodeSize.height / 2,
            );
          }
        }}
      >
        {nodes.length === 0 && empty ? (
          <p className="pointer-events-none absolute inset-0 grid place-items-center px-4 text-center text-sm text-fg-muted">
            {empty}
          </p>
        ) : null}
        <div
          className="pointer-events-none absolute left-0 top-0 origin-top-left"
          style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})`, width, height }}
        >
          <svg width={width} height={height} className="absolute inset-0 overflow-visible" aria-hidden="true">
            {editor ? (
              <defs>
                <marker id={arrowId} markerWidth="7" markerHeight="8" refX="10" refY="4" orient="auto">
                  <path
                    d="M1 1l4 3-4 3"
                    fill="none"
                    stroke="var(--dag-line)"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </marker>
              </defs>
            ) : null}
            {nodes.flatMap((node) =>
              node.inputs.map((input) => {
                const from = positions.get(input);
                const to = positions.get(node.id);
                return from && to ? (
                  <path
                    key={`${input}->${node.id}`}
                    d={edgePath(from, to, nodeSize)}
                    markerEnd={editor ? `url(#${arrowId})` : undefined}
                    fill="none"
                    stroke="var(--dag-line)"
                    strokeWidth={Math.min(8, Math.max(1, edgeWidth?.(input, node.id) ?? 2))}
                  />
                ) : null;
              }),
            )}
          </svg>
          {nodes.map((node) => {
            const p = positions.get(node.id);
            if (!p) return null;
            return (
              <Fragment key={node.id}>
                <button
                  type="button"
                  aria-label={node.label}
                  aria-pressed={onSelect && node.selected !== undefined ? node.selected : undefined}
                  data-kind={node.kind}
                  onClick={(event) => {
                    if (!movedNode.current || event.detail === 0) onSelect?.(node.id);
                    movedNode.current = false;
                  }}
                  onPointerDown={
                    editing
                      ? (event) => {
                          if (event.button !== 0) return;
                          movedNode.current = false;
                          nodeDrag.current = {
                            id: node.id,
                            start: { x: event.clientX, y: event.clientY },
                            position: p,
                          };
                          event.currentTarget.setPointerCapture?.(event.pointerId);
                        }
                      : undefined
                  }
                  onPointerMove={
                    editing
                      ? (event) => {
                          const current = nodeDrag.current;
                          if (current?.id !== node.id) return;
                          const dx = event.clientX - current.start.x;
                          const dy = event.clientY - current.start.y;
                          if (!movedNode.current && Math.hypot(dx, dy) < 4) return;
                          movedNode.current = true;
                          positionNode(
                            node.id,
                            current.position.x + dx / view.scale,
                            current.position.y + dy / view.scale,
                          );
                        }
                      : undefined
                  }
                  onPointerUp={
                    editing
                      ? () => {
                          nodeDrag.current = null;
                        }
                      : undefined
                  }
                  onLostPointerCapture={
                    editing
                      ? () => {
                          nodeDrag.current = null;
                        }
                      : undefined
                  }
                  className={cx(
                    "dag-node pointer-events-auto absolute flex gap-2 rounded-card border bg-surface px-3 text-left shadow-card",
                    "transition-colors duration-fast ease-out hover:border-primary",
                    editor ? "items-start" : "items-center",
                    editing && "cursor-move",
                    node.status === "error"
                      ? "border-danger"
                      : node.status === "warning" || node.emphasis
                        ? "border-warning"
                        : node.selected
                          ? "border-primary"
                          : "border-border",
                    node.emphasis && "ring-2 ring-warning-soft",
                  )}
                  style={{ left: p.x, top: p.y, width: nodeSize.width, height: nodeSize.height }}
                >
                  <span className="dag-node-icon flex size-8 shrink-0 items-center justify-center rounded-control">
                    <Icon name={KIND_ICONS[node.kind]} />
                  </span>
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium text-fg">{node.id}</span>
                    <span className="block truncate text-xs text-fg-muted">{node.detail}</span>
                  </span>
                  {node.status === "error" ? <Icon name="alert" className="ml-auto shrink-0 text-danger" /> : null}
                  {node.emphasis ? <Icon name="alert" className="ml-auto shrink-0 text-warning" /> : null}
                  {editor && node.kindLabel ? <span className="editor-canvas-node-kind">{node.kindLabel}</span> : null}
                  {node.metric ? (
                    <span className="absolute inset-x-0 top-full mt-1 truncate text-center text-xs tabular-nums text-fg-muted">
                      {node.metric}
                    </span>
                  ) : null}
                </button>
                {editing && node.kind !== "source" ? (
                  <button
                    type="button"
                    className="dag-port"
                    aria-label={editing.inputLabel(node.id)}
                    data-connect-target={connectFrom && editing.canConnect(connectFrom, node.id) ? "" : undefined}
                    style={{ left: p.x, top: p.y + nodeSize.height / 2 }}
                    onClick={() => connectTo(node.id)}
                    onDragOver={(event) => {
                      if (!connectFrom || !editing.canConnect(connectFrom, node.id)) return;
                      event.preventDefault();
                      event.dataTransfer.dropEffect = "link";
                    }}
                    onDrop={(event) => {
                      const from = event.dataTransfer.getData(PORT_DRAG);
                      if (!from) return;
                      event.preventDefault();
                      event.stopPropagation();
                      connectTo(node.id, from);
                    }}
                  />
                ) : null}
                {editing && node.kind !== "sink" ? (
                  <button
                    type="button"
                    className="dag-port"
                    aria-label={editing.outputLabel(node.id)}
                    aria-pressed={connectFrom === node.id}
                    draggable
                    style={{ left: p.x + nodeSize.width, top: p.y + nodeSize.height / 2 }}
                    onClick={() => setConnectFrom((current) => (current === node.id ? undefined : node.id))}
                    onDragStart={(event) => {
                      event.dataTransfer.setData(PORT_DRAG, node.id);
                      event.dataTransfer.effectAllowed = "link";
                      setConnectFrom(node.id);
                    }}
                    onDragEnd={() => setConnectFrom(undefined)}
                  />
                ) : null}
              </Fragment>
            );
          })}
        </div>
      </div>
      <div
        className={cx(
          "dag-tools absolute right-2 flex items-center gap-1 rounded-control border border-border bg-surface p-0.5 shadow-card",
          editor ? "bottom-3" : "top-2",
        )}
      >
        <IconButton size="sm" icon="zoomOut" label={labels.zoomOut} onClick={() => zoom(1 / 1.2)} />
        {editor ? (
          <span className="min-w-9 text-center text-xs tabular-nums text-fg-muted">
            {Math.round(view.scale * 100)}%
          </span>
        ) : null}
        <IconButton size="sm" icon="zoomIn" label={labels.zoomIn} onClick={() => zoom(1.2)} />
        <IconButton size="sm" icon="fit" label={labels.fit} onClick={fitVisible} />
      </div>
    </div>
  );
}
