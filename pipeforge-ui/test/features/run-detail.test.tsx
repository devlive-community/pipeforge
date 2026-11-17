/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { evidenceValue, rates, waitShare } from "@/features/run-detail/analysis";
import { edgeWidthFor } from "@/features/run-detail/PlanTab";
import { formatBytes } from "@/features/runs/format";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp } from "../render";

const ID = "8f6c1b2e-0000-4000-8000-000000000001";

const RUN = {
  id: ID,
  job: "orders-daily",
  status: "RUNNING",
  submittedAt: "2026-11-17T08:00:00Z",
  startedAt: "2026-11-17T08:00:01Z",
  updatedAt: "2026-11-17T08:01:00Z",
};

const METRICS = {
  elapsedMs: 60_000,
  parallelism: 2,
  rowsRead: 120_000,
  rowsWritten: 118_000,
  rowsInFlight: 2_000,
  rowsDirty: 3,
  readRowsPerSecond: 2_000,
  writeRowsPerSecond: 1_966.7,
  readerWaitMs: 84_000,
  writerWaitMs: 0,
  throttleWaitMs: 0,
  backpressuredMs: 0,
  splitsTotal: 8,
  splitsFinished: 6,
  nodes: [
    { node: "in", rowsIn: 0, rowsOut: 120_000 },
    { node: "out", rowsIn: 118_000, rowsOut: 118_000 },
  ],
};

const ANALYSIS = {
  schemaVersion: "1",
  job: "orders-daily",
  status: "RUNNING",
  windowFromMs: 0,
  windowToMs: 60_000,
  rates: [
    { node: "in", rowsInPerSecond: 0, rowsOutPerSecond: 2000 },
    { node: "out", rowsInPerSecond: 1966.7, rowsOutPerSecond: 1966.7 },
  ],
  bottleneck: {
    kind: "TARGET",
    node: "out",
    evidence: [{ metric: "readerWaitShare", node: null, value: 0.7 }],
    advice: [
      { code: "sink-parallelism", text: "Raise the parallelism of out" },
      { code: "some-new-rule", text: "Something the UI does not know yet" },
    ],
  },
};

const SAMPLES = [
  { at: "2026-11-17T08:00:10Z", rowsRead: 0, rowsWritten: 0 },
  { at: "2026-11-17T08:00:20Z", rowsRead: 20_000, rowsWritten: 19_000 },
  { at: "2026-11-17T08:00:30Z", rowsRead: 40_000, rowsWritten: 39_000 },
];

const DEFINITION = `version: 1
name: orders-daily
source:
  - id: in
    type: fake
sink:
  - id: out
    type: jdbc
    inputs: [in]
`;

function server(overrides: Record<string, unknown> = {}) {
  return fakeServer({
    [`/api/v1/runs/${ID}`]: { run: RUN, definition: DEFINITION, transitions: [] },
    [`/api/v1/runs/${ID}/metrics`]: METRICS,
    [`/api/v1/runs/${ID}/analysis`]: ANALYSIS,
    [`/api/v1/runs/${ID}/metrics/history`]: SAMPLES,
    ...overrides,
  });
}

describe("RunDetailPage", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("shows the run, its progress, waits and bottleneck", async () => {
    server();
    renderApp(`/runs/${ID}`);
    expect(await screen.findByRole("heading", { level: 1, name: "orders-daily" })).toBeInTheDocument();
    expect(screen.getByText("Running")).toBeInTheDocument();
    expect(screen.getByText(ID)).toBeInTheDocument();
    expect(await screen.findByText("6 of 8 splits read")).toBeInTheDocument();
    expect(screen.getByText("75%")).toBeInTheDocument();
    // 84 s of waiting over 60 s of two tasks
    const readerWait = screen.getByText("Readers waited for the target").parentElement as HTMLElement;
    expect(within(readerWait).getByText("70%")).toBeInTheDocument();
    expect(await screen.findByText("The target is the slowest part")).toBeInTheDocument();
    expect(screen.getByText("at out")).toBeInTheDocument();
    expect(screen.getByText("Raise the parallelism of out.")).toBeInTheDocument();
    expect(screen.getByText("Something the UI does not know yet")).toBeInTheDocument();
  });

  it("draws the metrics over time in the metrics tab, kept in the address", async () => {
    server();
    const { router } = renderApp(`/runs/${ID}`);
    await userEvent.click(await screen.findByRole("tab", { name: "Metrics" }));
    expect(router.state.location.search).toBe("?tab=metrics");
    expect(
      await screen.findByRole("img", {
        name: /Read and write rates\. Now 2,000 rows\/s read and 2,000 rows\/s written/,
      }),
    ).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /40,000 rows read and 39,000 rows written/ })).toBeInTheDocument();
    const nodes = screen.getByRole("table", { name: "Nodes" });
    expect(within(nodes).getAllByText("118,000")).toHaveLength(2);
  });

  it("draws the execution graph with rows, rates and the bottleneck", async () => {
    server();
    const { router } = renderApp(`/runs/${ID}?tab=plan`);
    const graph = await screen.findByRole("group", { name: "Execution graph of the run" });
    expect(within(graph).getByText("120K rows · 2K/s")).toBeInTheDocument();
    // a sink shows the rows it took
    expect(within(graph).getByText("118K rows · 2K/s")).toBeInTheDocument();
    const sink = within(graph).getByRole("button", { name: "out, sink jdbc, the bottleneck of the run" });
    expect(screen.getByText("Choose a node in the graph to see its rows, bytes and rates.")).toBeInTheDocument();
    expect(screen.getByText("The target is the slowest part")).toBeInTheDocument();

    await userEvent.click(sink);
    expect(router.state.location.search).toBe("?tab=plan&node=out");
    expect(sink).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("heading", { level: 2, name: "out" })).toBeInTheDocument();
    expect(screen.getByText("The run analysis finds this node to be the bottleneck; see below.")).toBeInTheDocument();
    expect(screen.getAllByText("1,967 rows/s")).toHaveLength(2);

    await userEvent.click(screen.getByRole("tab", { name: "Overview" }));
    expect(router.state.location.search).toBe("");
  });

  it("explains a run without a definition", async () => {
    server({ [`/api/v1/runs/${ID}`]: { run: RUN, transitions: [] } });
    renderApp(`/runs/${ID}?tab=plan`);
    expect(
      await screen.findByText("The server has no definition of this run, so there is no graph to show."),
    ).toBeInTheDocument();
  });

  it("explains a chart without samples and a run without analysis", async () => {
    server({
      [`/api/v1/runs/${ID}/analysis`]: undefined,
      [`/api/v1/runs/${ID}/metrics/history`]: [],
    });
    renderApp(`/runs/${ID}?tab=metrics`);
    expect(await screen.findAllByText(/The chart appears once the server has two samples/)).toHaveLength(2);
    await userEvent.click(screen.getByRole("tab", { name: "Overview" }));
    expect(await screen.findByText("There is no analysis of this run.")).toBeInTheDocument();
  });

  it("says when the run does not exist", async () => {
    fakeServer({ [`/api/v1/runs/${ID}`]: [404, { code: "PF-CLUS-0014", message: "There is no run" }] });
    renderApp(`/runs/${ID}`);
    expect(await screen.findByText(`There is no run ${ID}.`)).toBeInTheDocument();
  });

  it("is reached from the run list and stays under Runs in the navigation", async () => {
    server({ "/api/v1/runs": { runs: [RUN] } });
    const { router } = renderApp("/runs");
    await userEvent.click(await screen.findByRole("link", { name: "orders-daily" }));
    expect(router.state.location.pathname).toBe(`/runs/${ID}`);
    expect(screen.getByRole("link", { name: "Runs", current: "page" })).toBeInTheDocument();
  });

  it("speaks Chinese", async () => {
    usePreferences.setState({ language: "zh-CN" });
    server();
    renderApp(`/runs/${ID}`);
    expect(await screen.findByText("目标端最慢")).toBeInTheDocument();
    expect(screen.getByText("调高 out 的并行度。")).toBeInTheDocument();
  });
});

describe("run analysis helpers", () => {
  it("derives rates between samples", () => {
    const { read, write } = rates(SAMPLES);
    expect(read.map((p) => p.y)).toEqual([2000, 2000]);
    expect(write.map((p) => p.y)).toEqual([1900, 2000]);
  });

  it("shows shares as percentages", () => {
    expect(evidenceValue("readerWaitShare", 0.704)).toBe("70%");
    expect(evidenceValue("rowsRead", 1234.56)).toBe("1,234.6");
    expect(waitShare({ elapsedMs: 0, parallelism: 1 }, 10)).toBe(0);
    expect(waitShare({ elapsedMs: 1000, parallelism: 1 }, 5000)).toBe(1);
  });

  it("sizes edges by rate and formats bytes", () => {
    expect(edgeWidthFor(undefined, 100)).toBeUndefined();
    expect(edgeWidthFor(0, 100)).toBe(1.5);
    expect(edgeWidthFor(100, 100)).toBe(8);
    expect(edgeWidthFor(50, 0)).toBeUndefined();
    expect(formatBytes(512, "en-US")).toBe("512 B");
    expect(formatBytes(1536, "en-US")).toBe("1.5 KiB");
    expect(formatBytes(3.2 * 1024 ** 3, "en-US")).toBe("3.2 GiB");
  });
});
