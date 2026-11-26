/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { RunAttempt } from "@pipeforge/client";
import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { attemptDuration, timeUntil } from "@/features/run-detail/AttemptsTab";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp } from "../render";

const ID = "8f6c1b2e-0000-4000-8000-000000000001";

const RUN = {
  id: ID,
  job: "orders-daily",
  status: "RETRY_WAIT",
  submittedAt: "2026-11-26T08:00:00Z",
  startedAt: "2026-11-26T08:00:01Z",
  updatedAt: "2026-11-26T08:10:00Z",
};

const FIRST: RunAttempt = {
  attempt: 1,
  status: "FAILED",
  startedAt: "2026-11-26T08:00:01Z",
  endedAt: "2026-11-26T08:04:01Z",
  rowsRead: 1_234_567,
  rowsWritten: 1_234_000,
  rowsDirty: 3,
  elapsedMs: 240_000,
  error: { code: "PF-CONN-0003", message: "The connection to orders-db was lost" },
};

function server(attempts: unknown[]) {
  return fakeServer({
    [`/api/v1/runs/${ID}`]: { run: RUN, definition: "version: 1\n", transitions: [] },
    [`/api/v1/runs/${ID}/metrics`]: { elapsedMs: 0, nodes: [] },
    [`/api/v1/runs/${ID}/attempts`]: attempts,
  });
}

describe("the attempts of a run", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));
  afterEach(() => vi.useRealTimers());

  it("measures attempts and the time to the next", () => {
    expect(timeUntil("2026-11-26T08:00:30Z", Date.parse("2026-11-26T08:00:00Z"))).toBe(30_000);
    expect(timeUntil("2026-11-26T08:00:00Z", Date.parse("2026-11-26T08:00:30Z"))).toBe(0);
    expect(timeUntil(undefined, 0)).toBeNull();
    expect(attemptDuration(FIRST)).toBe(240_000);
    expect(attemptDuration({ ...FIRST, elapsedMs: undefined } as RunAttempt)).toBe(240_000);
    expect(attemptDuration({ attempt: 2, startedAt: "2026-11-26T08:05:00Z" })).toBeNull();
  });

  it("lists each attempt, newest first, with why it ended and what it did, kept in the address", async () => {
    server([
      FIRST,
      {
        attempt: 2,
        status: "RUNNING",
        startedAt: "2026-11-26T08:05:00Z",
        restorePath: "checkpoints/run/chk-6/",
        rowsRead: 50,
        rowsWritten: 48,
        rowsDirty: 0,
        elapsedMs: 61_000,
      },
    ]);
    const { router } = renderApp(`/runs/${ID}`);
    await userEvent.click(await screen.findByRole("tab", { name: "Attempts" }));
    expect(router.state.location.search).toBe("?tab=attempts");
    const list = await screen.findByRole("list", { name: "Attempts of the run, newest first" });
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    // the newest first
    const second = within(items[0] as HTMLElement);
    expect(second.getByRole("heading", { name: "Attempt #2" })).toBeInTheDocument();
    expect(second.getByText("Running")).toBeInTheDocument();
    expect(second.getByText("checkpoints/run/chk-6/")).toBeInTheDocument();
    const first = within(items[1] as HTMLElement);
    expect(first.getByText("Failed")).toBeInTheDocument();
    expect(first.getByText("4 m 00 s")).toBeInTheDocument();
    expect(first.getByText("1,234,567")).toBeInTheDocument();
    expect(first.getByText("The beginning")).toBeInTheDocument();
    expect(first.getByText("PF-CONN-0003")).toBeInTheDocument();
    expect(first.getByText("The connection to orders-db was lost", { exact: false })).toBeInTheDocument();
    expect(screen.queryByRole("timer")).not.toBeInTheDocument();
  });

  it("counts down to the next attempt while the run waits to be retried", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, now: Date.parse("2026-11-26T08:05:00Z") });
    server([{ ...FIRST, retryAt: "2026-11-26T08:05:30Z" }]);
    renderApp(`/runs/${ID}?tab=attempts`);
    const timer = await screen.findByRole("timer");
    expect(timer).toHaveTextContent(/Attempt #2 starts in (29|30) s, at/);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(timer).toHaveTextContent(/Attempt #2 starts in (19|20) s/);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(25_000);
    });
    expect(screen.getByRole("timer")).toHaveTextContent("Attempt #2 starts now");
  });

  it("says when there is none", async () => {
    server([]);
    renderApp(`/runs/${ID}?tab=attempts`);
    expect(await screen.findByText("No attempt yet")).toBeInTheDocument();
  });

  it("speaks Chinese", async () => {
    usePreferences.setState({ language: "zh-CN" });
    server([{ ...FIRST, retryAt: new Date(Date.now() + 90_000).toISOString() }]);
    renderApp(`/runs/${ID}?tab=attempts`);
    expect(await screen.findByRole("list", { name: "运行的各次尝试，从新到旧" })).toBeInTheDocument();
    expect(screen.getByRole("timer")).toHaveTextContent(/第 2 次尝试将在 1 m 2\d s后开始/);
  });
});
