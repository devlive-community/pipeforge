/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";
import { cx } from "@/ui/cx";

/** The chart colors of the design tokens, 1 to 8. */
export type ChartColor = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8;

const STROKES: Record<ChartColor, string> = {
  1: "stroke-chart-1",
  2: "stroke-chart-2",
  3: "stroke-chart-3",
  4: "stroke-chart-4",
  5: "stroke-chart-5",
  6: "stroke-chart-6",
  7: "stroke-chart-7",
  8: "stroke-chart-8",
};

const SWATCHES: Record<ChartColor, string> = {
  1: "bg-chart-1",
  2: "bg-chart-2",
  3: "bg-chart-3",
  4: "bg-chart-4",
  5: "bg-chart-5",
  6: "bg-chart-6",
  7: "bg-chart-7",
  8: "bg-chart-8",
};

export interface ChartPoint {
  /** Milliseconds since the epoch. */
  x: number;
  y: number;
}

export interface ChartSeries {
  id: string;
  label: string;
  color: ChartColor;
  points: ChartPoint[];
}

const WIDTH = 600;
const HEIGHT = 200;

/** The y value at the top of the plot: a round number above the largest value. */
export function niceMax(max: number): number {
  if (max <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(max));
  for (const step of [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) {
    if (max <= step * magnitude) return step * magnitude;
  }
  return 10 * magnitude;
}

/**
 * Lines over time in SVG, one per series, with three levels of the y axis, the first and last time
 * on the x axis and a legend. The chart is an image for assistive technology, described by
 * `summary`; the numbers it shows are also on the page in text.
 */
export function LineChart({
  series,
  label,
  summary,
  formatY,
  formatX,
  empty,
  className,
}: {
  series: ChartSeries[];
  label: string;
  summary: string;
  formatY: (value: number) => string;
  formatX: (ms: number) => string;
  empty: ReactNode;
  className?: string;
}) {
  const points = series.flatMap((s) => s.points);
  if (points.length < 2) {
    return <div className={cx("flex h-48 items-center justify-center text-sm text-fg-muted", className)}>{empty}</div>;
  }
  const xs = points.map((p) => p.x);
  const from = Math.min(...xs);
  const to = Math.max(...xs);
  const top = niceMax(Math.max(...points.map((p) => p.y)));
  const x = (value: number) => (to === from ? 0 : ((value - from) / (to - from)) * WIDTH);
  const y = (value: number) => HEIGHT - (value / top) * HEIGHT;
  const levels = [top, top / 2, 0];
  return (
    <figure className={cx("min-w-0", className)}>
      <div className="flex gap-2">
        <div className="flex h-48 flex-col justify-between text-right text-xs tabular-nums text-fg-subtle">
          {levels.map((level) => (
            <span key={level}>{formatY(level)}</span>
          ))}
        </div>
        <div className="relative h-48 min-w-0 flex-1">
          <svg
            role="img"
            aria-label={`${label}. ${summary}`}
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
            preserveAspectRatio="none"
            className="absolute inset-0 size-full overflow-visible"
          >
            {levels.map((level) => (
              <line
                key={level}
                x1={0}
                x2={WIDTH}
                y1={y(level)}
                y2={y(level)}
                className="stroke-border"
                strokeWidth={1}
                vectorEffect="non-scaling-stroke"
              />
            ))}
            {series.map((s) =>
              s.points.length > 1 ? (
                <polyline
                  key={s.id}
                  points={s.points.map((p) => `${x(p.x)},${y(p.y)}`).join(" ")}
                  fill="none"
                  className={STROKES[s.color]}
                  strokeWidth={2}
                  strokeLinejoin="round"
                  vectorEffect="non-scaling-stroke"
                />
              ) : null,
            )}
          </svg>
        </div>
      </div>
      <div className="mt-1 flex justify-between pl-10 text-xs tabular-nums text-fg-subtle">
        <span>{formatX(from)}</span>
        <span>{formatX(to)}</span>
      </div>
      <figcaption className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-fg-muted">
        {series.map((s) => (
          <span key={s.id} className="inline-flex items-center gap-1.5">
            <span aria-hidden="true" className={cx("h-0.5 w-3 rounded-full", SWATCHES[s.color])} />
            {s.label}
          </span>
        ))}
      </figcaption>
    </figure>
  );
}
