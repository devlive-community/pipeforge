/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { RunCheckpoint } from "@pipeforge/client";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { checkpointDuration, checkpointSummary, longestAlignment } from "@/features/run-detail/CheckpointsTab";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp } from "../render";

const ID = "8f6c1b2e-0000-4000-8000-000000000001";

const RUN = {
  id: ID,
  job: "orders-daily",
  status: "RUNNING",
  submittedAt: "2026-11-26T08:00:00Z",
  startedAt: "2026-11-26T08:00:01Z",
  updatedAt: "2026-11-26T08:10:00Z",
};

const [FAILED, COMPLETED, SAVEPOINT]: [RunCheckpoint, RunCheckpoint, RunCheckpoint] = [
  {
    id: 9,
    attempt: 2,
    type: "CHECKPOINT",
    status: "FAILED",
    reason: "PERIODIC",
    triggeredAt: "2026-11-26T08:09:00Z",
    path: "run/chk-9/",
    error: { code: "PF-CKPT-0001", message: "Checkpoint 9 timed out" },
    tasks: [],
  },
  {
    id: 8,
    attempt: 2,
    type: "CHECKPOINT",
    status: "COMPLETED",
    reason: "PERIODIC",
    triggeredAt: "2026-11-26T08:08:00Z",
    completedAt: "2026-11-26T08:08:01.500Z",
    sizeBytes: 3072,
    path: "run/chk-8/",
    tasks: [
      { task: `${ID}/src/0/0`, states: 1, syncMs: 12, asyncMs: 300, alignmentMs: 0, stateBytes: 1024 },
      { task: `${ID}/out/1/0`, states: 1, syncMs: 4, asyncMs: 200, alignmentMs: 450, stateBytes: 2048 },
    ],
  },
  {
    id: 7,
    attempt: 1,
    type: "SAVEPOINT",
    status: "COMPLETED",
    reason: "SAVEPOINT",
    name: "before-upgrade",
    triggeredAt: "2026-11-26T08:05:00Z",
    completedAt: "2026-11-26T08:05:00.400Z",
    sizeBytes: 1536,
    path: "run/sp-7/",
    tasks: [{ task: `${ID}/src/0/0`, states: 1, syncMs: 2, asyncMs: 3, alignmentMs: 0, stateBytes: 1536 }],
  },
];

const CHECKPOINTS = [FAILED, COMPLETED, SAVEPOINT];

function server(overrides: Record<string, unknown> = {}) {
  return fakeServer({
    [`/api/v1/runs/${ID}`]: { run: RUN, definition: "version: 1\nname: orders-daily\n", transitions: [] },
    [`/api/v1/runs/${ID}/metrics`]: { elapsedMs: 0, nodes: [] },
    [`/api/v1/runs/${ID}/analysis`]: [404, { code: "PF-CONF-0010", message: "none" }],
    [`/api/v1/runs/${ID}/checkpoints`]: CHECKPOINTS,
    ...overrides,
  });
}

describe("the checkpoints of a run", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("adds them up and measures each", () => {
    const summary = checkpointSummary(CHECKPOINTS);
    expect(summary.last?.id).toBe(8);
    expect(summary.completed).toBe(1);
    expect(summary.savepoints).toBe(1);
    expect(summary.failed).toBe(1);
    expect(checkpointDuration(COMPLETED)).toBe(1500);
    expect(checkpointDuration(FAILED)).toBeNull();
    expect(longestAlignment(COMPLETED)).toBe(450);
    expect(longestAlignment(FAILED)).toBeNull();
    expect(checkpointSummary([]).last).toBeUndefined();
  });

  it("lists them newest first with their tasks, and keeps the chosen one in the address", async () => {
    server();
    const { router } = renderApp(`/runs/${ID}`);
    await userEvent.click(await screen.findByRole("tab", { name: "Checkpoints" }));
    expect(router.state.location.search).toBe("?tab=checkpoints");

    const table = await screen.findByRole("table", { name: "Checkpoints and savepoints of the run, newest first" });
    const rows = within(table).getAllByRole("row");
    const row = (index: number) => rows[index] as HTMLElement;
    expect(rows).toHaveLength(4);
    expect(within(row(1)).getByText("Failed")).toBeInTheDocument();
    expect(within(row(1)).getByText("Checkpoint 9 timed out")).toBeInTheDocument();
    expect(within(row(2)).getByText("1 s")).toBeInTheDocument();
    expect(within(row(2)).getByText("450 ms")).toBeInTheDocument();
    expect(within(row(2)).getByText("3 KiB")).toBeInTheDocument();
    expect(within(row(3)).getByText("Savepoint")).toBeInTheDocument();
    expect(within(row(3)).getByText("before-upgrade")).toBeInTheDocument();
    expect(screen.getByText("#8 at", { exact: false })).toBeInTheDocument();

    // the last completed one is shown until another is chosen
    const tasks = screen.getByRole("table", { name: "Tasks of checkpoint #8" });
    expect(within(tasks).getAllByRole("row")).toHaveLength(3);
    expect(within(tasks).getByText("300 ms")).toBeInTheDocument();
    expect(within(tasks).getByText("2 KiB")).toBeInTheDocument();

    await userEvent.click(within(table).getByRole("button", { name: "#7" }));
    expect(router.state.location.search).toBe("?tab=checkpoints&checkpoint=7");
    expect(await screen.findByRole("table", { name: "Tasks of checkpoint #7" })).toBeInTheDocument();
    expect(screen.getByText("run/sp-7/")).toBeInTheDocument();

    await userEvent.click(within(table).getByRole("button", { name: "#9" }));
    const failed = await screen.findByRole("table", { name: "Tasks of checkpoint #9" });
    expect(within(failed).getByText("No task took part yet.")).toBeInTheDocument();
  });

  it("takes a savepoint of a running run with an optional name", async () => {
    const requests = server({
      [`/api/v1/runs/${ID}/savepoint`]: { ...SAVEPOINT, id: 10, path: "run/sp-10/" },
    });
    renderApp(`/runs/${ID}?tab=checkpoints`);
    await userEvent.type(await screen.findByLabelText("Name"), "nightly");
    await userEvent.click(screen.getByRole("button", { name: "Take savepoint" }));
    expect(await screen.findByText("Savepoint #10 completed at run/sp-10/")).toBeInTheDocument();
    const sent = requests.find((r) => r.path.endsWith("/savepoint"));
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({ name: "nightly" });
    expect(screen.getByLabelText("Name")).toHaveValue("");
    // the list was asked again
    expect(requests.filter((r) => r.path.endsWith("/checkpoints")).length).toBeGreaterThan(1);
  });

  it("says why a savepoint failed", async () => {
    server({
      [`/api/v1/runs/${ID}/savepoint`]: [400, { code: "PF-CONF-0007", message: "run is PAUSED" }],
    });
    renderApp(`/runs/${ID}?tab=checkpoints`);
    await userEvent.click(await screen.findByRole("button", { name: "Take savepoint" }));
    expect(await screen.findByText("The savepoint failed: run is PAUSED")).toBeInTheDocument();
  });

  it("offers no savepoint for a run that is not running", async () => {
    server({
      [`/api/v1/runs/${ID}`]: { run: { ...RUN, status: "SUCCESS", endedAt: "2026-11-26T09:00:00Z" }, transitions: [] },
      [`/api/v1/runs/${ID}/checkpoints`]: [],
    });
    renderApp(`/runs/${ID}?tab=checkpoints`);
    expect(await screen.findByText("No checkpoint yet")).toBeInTheDocument();
    expect(screen.getByText("Only a running run takes a savepoint.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Take savepoint" })).not.toBeInTheDocument();
  });

  it("speaks Chinese", async () => {
    usePreferences.setState({ language: "zh-CN" });
    server();
    renderApp(`/runs/${ID}?tab=checkpoints`);
    expect(await screen.findByRole("table", { name: "运行的 checkpoint 与 savepoint，从新到旧" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "做 savepoint" })).toBeInTheDocument();
  });
});
