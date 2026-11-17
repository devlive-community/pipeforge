/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { formatDuration, runDuration } from "@/features/runs/format";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp } from "../render";

const RUN = {
  id: "8f6c1b2e-0000-4000-8000-000000000001",
  job: "orders-daily",
  status: "SUCCESS",
  submittedAt: "2026-11-17T08:00:00Z",
  startedAt: "2026-11-17T08:00:01Z",
  endedAt: "2026-11-17T08:03:06Z",
  updatedAt: "2026-11-17T08:03:06Z",
  rowsRead: 1234567,
  rowsWritten: 1234560,
};

const FAILED = {
  ...RUN,
  id: "8f6c1b2e-0000-4000-8000-000000000002",
  job: "users-sync",
  status: "FAILED",
  rowsRead: null,
  rowsWritten: null,
  error: { code: "PF-CLUS-0016", message: "The master no longer knows the run" },
};

describe("RunsPage", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));
  afterEach(() => vi.useRealTimers());

  it("lists runs with their state, duration, counts and errors", async () => {
    fakeServer({ "/api/v1/runs": { runs: [RUN, FAILED] } });
    renderApp("/runs");
    expect(await screen.findByText("orders-daily")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Runs" })).toBeInTheDocument();
    const rows = screen.getAllByRole("row");
    expect(within(rows[1] as HTMLElement).getByText("Succeeded")).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByText("3 m 05 s")).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByText("1,234,567")).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText("PF-CLUS-0016")).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getAllByText("–")).toHaveLength(2);
  });

  it("filters by state group and job, in the address and the request", async () => {
    const requests = fakeServer({ "/api/v1/runs": { runs: [] } });
    const { router } = renderApp("/runs");
    expect(await screen.findByText("No runs yet")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: "Failed" }));
    expect(await screen.findByText("No run matches the filters.")).toBeInTheDocument();
    expect(router.state.location.search).toBe("?state=failed");
    expect(requests.at(-1)?.search).toContain("status=FAILED&status=TIMEOUT");
    await userEvent.type(screen.getByRole("textbox", { name: "Job name" }), "orders");
    expect(router.state.location.search).toBe("?state=failed&job=orders");
    expect(requests.at(-1)?.search).toContain("job=orders");
  });

  it("pages with the token of the next page", async () => {
    const requests = fakeServer({
      "/api/v1/runs": () =>
        requests.at(-1)?.search.includes("page=t1") ? { runs: [FAILED] } : { runs: [RUN], next: "t1" },
    });
    renderApp("/runs");
    expect(await screen.findByText("orders-daily")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous page" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Next page" }));
    expect(await screen.findByText("users-sync")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Previous page" }));
    expect(await screen.findByText("orders-daily")).toBeInTheDocument();
  });

  it("asks again every two seconds, but not while the page is hidden", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const requests = fakeServer({ "/api/v1/runs": { runs: [RUN] } });
    renderApp("/runs");
    expect(await screen.findByText("orders-daily")).toBeInTheDocument();
    const before = requests.length;
    await act(() => vi.advanceTimersByTimeAsync(2100));
    expect(requests.length).toBe(before + 1);

    const visibility = vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
    const hidden = requests.length;
    await act(() => vi.advanceTimersByTimeAsync(6000));
    expect(requests.length).toBe(hidden);
    visibility.mockRestore();
  });

  it("speaks Chinese", async () => {
    usePreferences.setState({ language: "zh-CN" });
    fakeServer({ "/api/v1/runs": { runs: [RUN] } });
    renderApp("/runs");
    expect(await screen.findByText("成功")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "运行" })).toBeInTheDocument();
  });
});

describe("run durations", () => {
  it("shows the two largest units", () => {
    expect(formatDuration(850)).toBe("850 ms");
    expect(formatDuration(12_400)).toBe("12 s");
    expect(formatDuration(185_000)).toBe("3 m 05 s");
    expect(formatDuration(7_620_000)).toBe("2 h 07 m");
    expect(formatDuration(null)).toBe("–");
  });

  it("counts to now while a run runs", () => {
    const now = Date.parse("2026-11-17T08:00:31Z");
    expect(runDuration({ startedAt: "2026-11-17T08:00:01Z" }, now)).toBe(30_000);
    expect(runDuration({ startedAt: null }, now)).toBeNull();
  });
});
