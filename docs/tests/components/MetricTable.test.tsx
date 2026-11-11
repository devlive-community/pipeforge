/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { join } from "node:path";
import { render, screen } from "@testing-library/react";
import { MetricTable } from "@/components/mdx/MetricList";
import type { MetricEntry } from "@/lib/generated";
import { collectMetrics } from "@/scripts/metrics-data.mjs";

const entries: MetricEntry[] = [
  {
    name: "rowsRead",
    scope: "job",
    type: "counter",
    unit: "rows",
    description: { "en-US": "Rows read.", "zh-CN": "读取的行数。" },
  },
  {
    name: "selfTimeRatio",
    scope: "node",
    type: "gauge",
    unit: "ratio",
    description: { "en-US": "Own time." },
  },
];

describe("MetricTable", () => {
  it("renders a table per scope with an anchor per metric", () => {
    render(<MetricTable entries={entries} lang="en-US" />);
    expect(screen.getByRole("region", { name: "Job metrics" })).toBeInTheDocument();
    expect(document.getElementById("job-rowsread")).toHaveTextContent("rowsReadcounterrowsRows read.");
    expect(document.getElementById("node-selftimeratio")).toHaveTextContent("Own time.");
  });

  it("uses the page language and falls back to English", () => {
    render(<MetricTable entries={entries} lang="zh-CN" />);
    expect(screen.getByRole("region", { name: "作业级指标" })).toBeInTheDocument();
    expect(screen.getByText("读取的行数。")).toBeInTheDocument();
    expect(screen.getByText("Own time.")).toBeInTheDocument();
    expect(screen.getAllByRole("columnheader", { name: "含义" })).toHaveLength(2);
  });

  it("omits scopes without metrics", () => {
    render(<MetricTable entries={entries.slice(0, 1)} lang="en-US" />);
    expect(screen.queryByRole("region", { name: "Node metrics" })).not.toBeInTheDocument();
  });

  it("reads the real catalog, job metrics first", () => {
    const metrics = collectMetrics(join(process.cwd(), ".."));
    expect(metrics.length).toBeGreaterThan(10);
    expect(metrics[0]).toMatchObject({ name: "elapsedMs", scope: "job", unit: "ms" });
    expect(metrics.at(-1)?.scope).toBe("node");
    expect(
      metrics.every((m: { description: Record<string, string> }) => m.description["en-US"] && m.description["zh-CN"]),
    ).toBe(true);
    expect(collectMetrics("/nonexistent")).toEqual([]);
  });
});
