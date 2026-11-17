/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { keepEnd, logLines, marked, parseTaskKey, taskKey } from "@/features/run-detail/logs";
import { splitProgress } from "@/features/run-detail/SplitsTab";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp, type Recorded } from "../render";

const ID = "8f6c1b2e-0000-4000-8000-000000000001";
const RUN = {
  id: ID,
  job: "orders-daily",
  status: "RUNNING",
  submittedAt: "2026-11-17T08:00:00Z",
  startedAt: "2026-11-17T08:00:01Z",
};

const SPLITS = [
  { node: "orders", split: "orders[0..1000)", subtask: 0, state: "FINISHED" },
  { node: "orders", split: "orders[1000..2000)", subtask: 1, state: "ASSIGNED" },
  { node: "users", split: "users[0..10)", subtask: 0, state: "FINISHED" },
];

const TASKS = [
  { vertex: "orders", subtask: 0, attempt: 0, state: "RUNNING", worker: "worker-1" },
  { vertex: "orders", subtask: 1, attempt: 1, state: "RUNNING", worker: "worker-2" },
];

const LOG = [
  "2026-11-17 08:00:02.120 INFO  [task-0] o.d.p.Reader - reading orders[0..1000)",
  "2026-11-17 08:00:03.540 WARN  [task-0] o.d.p.Reader - slow query: 2.1 s",
  "2026-11-17 08:00:04.002 ERROR [task-0] o.d.p.Writer - write failed",
  "java.sql.SQLException: Data too long",
  "",
].join("\n");

function query(request: Recorded | undefined) {
  return new URLSearchParams(request?.search ?? "");
}

function server(log: (request: URLSearchParams) => unknown) {
  const requests: Recorded[] = fakeServer({
    [`/api/v1/runs/${ID}`]: { run: RUN, transitions: [] },
    [`/api/v1/runs/${ID}/splits`]: SPLITS,
    [`/api/v1/runs/${ID}/tasks`]: TASKS,
    [`/api/v1/runs/${ID}/logs`]: () => log(query(requests.filter((r) => r.path.endsWith("/logs")).at(-1))),
  });
  return requests;
}

const logRequests = (requests: Recorded[]) => requests.filter((r) => r.path.endsWith("/logs")).map(query);

describe("run splits", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("shows the progress of each source and every split with its reader", async () => {
    server(() => ({ text: "", offset: 0, eof: false }));
    renderApp(`/runs/${ID}?tab=splits`);
    expect(await screen.findByText("1 of 2 splits read")).toBeInTheDocument();
    expect(screen.getByText("1 of 1 splits read")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Splits of the run's sources" });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(4);
    expect(within(rows[2] as HTMLElement).getByText("orders[1000..2000)")).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText("#1")).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText("Reading")).toBeInTheDocument();
  });

  it("counts finished splits per node", () => {
    expect(splitProgress(SPLITS)).toEqual([
      { node: "orders", finished: 1, total: 2 },
      { node: "users", finished: 1, total: 1 },
    ]);
  });
});

describe("run logs", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));
  afterEach(() => vi.useRealTimers());

  it("shows the end of the first task's log with its levels", async () => {
    const requests = server(() => ({ text: LOG, offset: 5000, eof: false }));
    renderApp(`/runs/${ID}?tab=logs`);
    const view = await screen.findByRole("log", { name: "Log of the task" });
    expect(await within(view).findByText(/write failed/)).toHaveClass("text-danger");
    expect(within(view).getByText(/slow query/)).toHaveClass("text-warning");
    // the stack trace belongs to the error above it
    expect(within(view).getByText("java.sql.SQLException: Data too long")).toHaveClass("text-danger");
    const first = logRequests(requests)[0]!;
    expect(first.get("vertex")).toBe("orders");
    expect(first.get("subtask")).toBe("0");
    expect(first.get("offset")).toBe("-65536");
    expect(screen.getByRole("button", { name: "Task" })).toHaveTextContent("orders #0 · worker-1 · RUNNING");
  });

  it("filters by level and text on the server and marks the matches", async () => {
    const requests = server((q) =>
      q.get("contains") ? { text: LOG.split("\n")[1] + "\n", offset: 5000, eof: true } : { text: LOG, offset: 5000 },
    );
    renderApp(`/runs/${ID}?tab=logs`);
    await screen.findByText(/write failed/);
    await userEvent.click(screen.getByRole("radio", { name: "Warn" }));
    expect(logRequests(requests).at(-1)?.get("level")).toBe("WARN");
    await userEvent.type(screen.getByRole("textbox", { name: "Find in the log" }), "slow");
    expect(await screen.findByText("The task ended; this is the end of its log.")).toBeInTheDocument();
    expect(logRequests(requests).at(-1)?.get("contains")).toBe("slow");
    expect(screen.getByText("slow", { selector: "mark" })).toBeInTheDocument();
    expect(screen.queryByText(/write failed/)).not.toBeInTheDocument();
  });

  it("switches tasks through the menu, kept in the address", async () => {
    const requests = server(() => ({ text: LOG, offset: 5000, eof: true }));
    const { router } = renderApp(`/runs/${ID}?tab=logs`);
    await screen.findByText(/write failed/);
    await userEvent.click(screen.getByRole("button", { name: "Task" }));
    await userEvent.click(screen.getByRole("menuitemradio", { name: "orders #1, attempt 1 · worker-2 · RUNNING" }));
    expect(router.state.location.search).toBe("?tab=logs&task=orders%2F1%2F1");
    await screen.findByText(/write failed/);
    const last = logRequests(requests).at(-1)!;
    expect([last.get("subtask"), last.get("attempt")]).toEqual(["1", "1"]);
  });

  it("follows the log from the offset of the last read until it ended", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let reads = 0;
    const requests = server(() => {
      reads++;
      if (reads === 1) return { text: LOG, offset: 5000, eof: false };
      return { text: "2026-11-17 08:00:09.000 INFO  [task-0] o.d.p.Reader - done\n", offset: 5060, eof: true };
    });
    renderApp(`/runs/${ID}?tab=logs`);
    await screen.findByText(/write failed/);
    await act(() => vi.advanceTimersByTimeAsync(2100));
    expect(await screen.findByText(/Reader - done/)).toBeInTheDocument();
    expect(logRequests(requests)[1]?.get("offset")).toBe("5000");
    await act(() => vi.advanceTimersByTimeAsync(6000));
    expect(logRequests(requests)).toHaveLength(2);
  });

  it("downloads the whole log, unfiltered", async () => {
    const created = vi.fn((blob: Blob) => {
      void blob;
      return "blob:log";
    });
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: created, revokeObjectURL: vi.fn() }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    const requests = server((q) =>
      q.get("offset") === "0"
        ? { text: "part one\n", offset: 9, eof: false }
        : q.get("offset") === "9"
          ? { text: "part two\n", offset: 18, eof: true }
          : { text: LOG, offset: 5000, eof: false },
    );
    renderApp(`/runs/${ID}?tab=logs`);
    await screen.findByText(/write failed/);
    await userEvent.click(screen.getByRole("button", { name: "Download" }));
    await vi.waitFor(() => expect(click).toHaveBeenCalled());
    const saved = await new Promise<string>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.readAsText(created.mock.calls[0]![0]);
    });
    expect(saved).toBe("part one\npart two\n");
    const anchor = click.mock.contexts[0] as HTMLAnchorElement;
    expect(anchor.download).toBe("orders-daily-orders-0-0.log");
    expect(logRequests(requests).filter((q) => q.get("maxBytes") === "1048576")).toHaveLength(2);
    click.mockRestore();
  });

  it("speaks Chinese", async () => {
    usePreferences.setState({ language: "zh-CN" });
    server(() => ({ text: "", offset: 0, eof: true }));
    renderApp(`/runs/${ID}?tab=logs`);
    expect(await screen.findByText("日志为空。")).toBeInTheDocument();
  });
});

describe("log helpers", () => {
  it("gives continuation lines the level of their entry", () => {
    expect(logLines(LOG).map((l) => l.level)).toEqual(["INFO", "WARN", "ERROR", "ERROR"]);
    expect(logLines("")).toEqual([]);
    expect(logLines("a\nb\nc\n", 2).map((l) => l.text)).toEqual(["b", "c"]);
  });

  it("keeps the end of a long log from a line", () => {
    expect(keepEnd("aaaa\nbbbb\ncccc\n", 8)).toBe("cccc\n");
    expect(keepEnd("short", 8)).toBe("short");
  });

  it("marks every match", () => {
    expect(marked("a slow and slow query", "slow")).toEqual([
      { text: "a ", match: false },
      { text: "slow", match: true },
      { text: " and ", match: false },
      { text: "slow", match: true },
      { text: " query", match: false },
    ]);
    expect(marked("text", "")).toEqual([{ text: "text", match: false }]);
  });

  it("names a task in the address", () => {
    const task = { vertex: "a/b", subtask: 2, attempt: 1 };
    expect(parseTaskKey(taskKey(task))).toEqual(task);
    expect(parseTaskKey("nonsense")).toBeNull();
    expect(parseTaskKey(null)).toBeNull();
  });
});
