/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import { cx } from "../ui/cx";
import { EDGES, NODE, NODES, STAGE, lineage } from "./dag";

const kindClass = {
  source: "border-chart-2",
  transform: "border-primary",
  sink: "border-chart-3",
};

/**
 * Data particles flow along the edges (CSS offset-path). Hovering or focusing a node
 * highlights everything upstream and downstream of it.
 */
export function HeroDag({ label }: { label: string }) {
  const [focus, setFocus] = useState<Set<string> | null>(null);
  const [scale, setScale] = useState(1);
  const frame = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const element = frame.current;
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setScale(Math.min(1, entry.contentRect.width / STAGE.width));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const active = (id: string) => focus === null || focus.has(id);

  return (
    <div ref={frame} role="img" aria-label={label} className="relative w-full" style={{ height: STAGE.height * scale }}>
      <div
        className="absolute top-0 left-0 origin-top-left"
        style={{ width: STAGE.width, height: STAGE.height, transform: `scale(${scale})` }}
      >
        <svg width={STAGE.width} height={STAGE.height} className="absolute inset-0" aria-hidden="true">
          {EDGES.map((edge) => (
            <path
              key={`${edge.from}-${edge.to}`}
              d={edge.path}
              fill="none"
              strokeWidth={2}
              className={cx(
                "transition-opacity duration-normal",
                active(edge.from) && active(edge.to) ? "stroke-border-strong opacity-100" : "stroke-border opacity-40",
              )}
            />
          ))}
        </svg>
        {EDGES.flatMap((edge, e) =>
          [0, 1, 2].map((i) => (
            <span
              key={`${edge.from}-${edge.to}-${i}`}
              aria-hidden="true"
              className={cx(
                "absolute top-0 left-0 size-2 rounded-full bg-primary animate-flow transition-opacity duration-normal",
                active(edge.from) && active(edge.to) ? "opacity-100" : "opacity-20",
              )}
              style={
                {
                  offsetPath: `path("${edge.path}")`,
                  offsetRotate: "0deg",
                  animationDelay: `${-(i * 0.8 + e * 0.3)}s`,
                } as CSSProperties
              }
            />
          )),
        )}
        {NODES.map((node) => (
          <div
            key={node.id}
            tabIndex={0}
            onMouseEnter={() => setFocus(lineage(node.id))}
            onMouseLeave={() => setFocus(null)}
            onFocus={() => setFocus(lineage(node.id))}
            onBlur={() => setFocus(null)}
            className={cx(
              "absolute flex items-center justify-center rounded-popover border-2 bg-surface text-sm font-medium text-fg shadow-card",
              "transition-[opacity,transform] duration-normal ease-out hover:-translate-y-0.5",
              kindClass[node.kind],
              active(node.id) ? "opacity-100" : "opacity-40",
            )}
            style={{ left: node.x, top: node.y, width: NODE.width, height: NODE.height }}
          >
            {node.label}
          </div>
        ))}
      </div>
    </div>
  );
}
