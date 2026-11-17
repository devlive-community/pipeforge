/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { RunMetrics } from "@pipeforge/client";

/** The run analysis as the server returns it (design 29, schema version 1). */
export interface RunAnalysis {
  schemaVersion: string;
  job: string;
  status: string;
  windowFromMs: number;
  windowToMs: number;
  rates: { node: string; rowsInPerSecond: number; rowsOutPerSecond: number }[];
  bottleneck: {
    kind: BottleneckKind;
    node: string | null;
    evidence: { metric: string; node: string | null; value: number }[];
    advice: { code: string; text: string }[];
  };
}

export type BottleneckKind = "THROTTLE" | "TARGET" | "NETWORK" | "SOURCE" | "NONE" | "UNKNOWN";

/** The advice codes the UI words itself; others are shown in the server's English. */
export const ADVICE_CODES = [
  "rate-limit",
  "sink-parallelism",
  "check-target",
  "network-buffers",
  "check-network",
  "source-parallelism",
  "check-source",
] as const;

export type AdviceCode = (typeof ADVICE_CODES)[number];

export function isAdviceCode(code: string): code is AdviceCode {
  return (ADVICE_CODES as readonly string[]).includes(code);
}

/** A share (0 to 1) as a percentage, other evidence as a number. */
export function evidenceValue(metric: string, value: number): string {
  return metric.endsWith("Share") || metric.endsWith("Ratio")
    ? `${Math.round(value * 100)}%`
    : new Intl.NumberFormat("en-US", { maximumFractionDigits: 1 }).format(value);
}

/** Read and write rates between consecutive samples, in rows per second. */
export function rates(samples: { at?: string; rowsRead?: number; rowsWritten?: number }[]) {
  const read: { x: number; y: number }[] = [];
  const write: { x: number; y: number }[] = [];
  for (let i = 1; i < samples.length; i++) {
    const before = samples[i - 1];
    const after = samples[i];
    if (!before?.at || !after?.at) continue;
    const seconds = (Date.parse(after.at) - Date.parse(before.at)) / 1000;
    if (seconds <= 0) continue;
    const x = Date.parse(after.at);
    read.push({ x, y: Math.max(0, ((after.rowsRead ?? 0) - (before.rowsRead ?? 0)) / seconds) });
    write.push({ x, y: Math.max(0, ((after.rowsWritten ?? 0) - (before.rowsWritten ?? 0)) / seconds) });
  }
  return { read, write };
}

/** The share of the run's time that tasks waited for something, from 0 to 1. */
export function waitShare(metrics: RunMetrics, waitedMs: number | undefined): number {
  const running = metrics.elapsedMs && metrics.parallelism ? metrics.elapsedMs * metrics.parallelism : 0;
  return running > 0 ? Math.min(1, (waitedMs ?? 0) / running) : 0;
}
