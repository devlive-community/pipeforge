/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { actionsOf } from "@/features/run-detail/RunControls";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp } from "../render";

const ID = "8f6c1b2e-0000-4000-8000-000000000001";

function run(status: string) {
  return {
    id: ID,
    job: "orders-daily",
    status,
    submittedAt: "2026-11-26T08:00:00Z",
    startedAt: "2026-11-26T08:00:01Z",
    updatedAt: "2026-11-26T08:10:00Z",
    ...(["FAILED", "CANCELLED", "TIMEOUT", "SUCCESS"].includes(status) ? { endedAt: "2026-11-26T09:00:00Z" } : {}),
  };
}

function server(status: string, overrides: Record<string, unknown> = {}) {
  return fakeServer({
    [`/api/v1/runs/${ID}`]: { run: run(status), definition: "version: 1\n", transitions: [] },
    [`/api/v1/runs/${ID}/metrics`]: { elapsedMs: 0, nodes: [] },
    [`/api/v1/runs/${ID}/checkpoints`]: [],
    ...overrides,
  });
}

const COMPLETED = { id: 4, type: "CHECKPOINT", status: "COMPLETED", path: "run/chk-4/", tasks: [] };

describe("the controls of a run", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("offers what the state of the run allows", () => {
    expect(actionsOf("RUNNING")).toEqual(["pause", "savepoint", "cancel"]);
    expect(actionsOf("PAUSED")).toEqual(["resume", "cancel"]);
    expect(actionsOf("QUEUE")).toEqual(["cancel"]);
    expect(actionsOf("FAILED")).toEqual(["retry"]);
    expect(actionsOf("TIMEOUT")).toEqual(["retry"]);
    expect(actionsOf("CANCELLED")).toEqual(["retry"]);
    expect(actionsOf("SUCCESS")).toEqual([]);
    expect(actionsOf("PAUSING")).toEqual([]);
    expect(actionsOf("CANCELLING")).toEqual([]);
  });

  it("pauses a running run with a savepoint name after asking", async () => {
    let paused = false;
    const requests = server("RUNNING", {
      [`/api/v1/runs/${ID}`]: () => ({ run: run(paused ? "PAUSING" : "RUNNING"), transitions: [] }),
      [`/api/v1/runs/${ID}/pause`]: () => {
        paused = true;
        return [202, run("PAUSING")];
      },
    });
    renderApp(`/runs/${ID}`);
    await userEvent.click(await screen.findByRole("button", { name: "Pause" }));
    const dialog = screen.getByRole("dialog", { name: "Pause orders-daily?" });
    await userEvent.type(within(dialog).getByLabelText("Savepoint name"), "nightly");
    await userEvent.click(within(dialog).getByRole("button", { name: "Pause" }));
    expect(await screen.findByText("Pausing")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    const sent = requests.find((r) => r.path.endsWith("/pause"));
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({ name: "nightly" });
    // a pausing run offers nothing until it is paused
    expect(screen.queryByRole("button", { name: "Pause" })).not.toBeInTheDocument();
  });

  it("keeps the dialog open with the error when the server refuses", async () => {
    server("RUNNING", {
      [`/api/v1/runs/${ID}/cancel`]: [400, { code: "PF-CONF-0007", message: "run is SUCCESS" }],
    });
    renderApp(`/runs/${ID}`);
    await userEvent.click(await screen.findByRole("button", { name: "Cancel" }));
    const dialog = screen.getByRole("dialog", { name: "Cancel orders-daily?" });
    await userEvent.type(within(dialog).getByLabelText("Reason"), "wrong day");
    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel the run" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The server refused: run is SUCCESS");
    await userEvent.click(within(dialog).getByRole("button", { name: "Not now" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("resumes a paused run and takes a savepoint of a running one", async () => {
    const requests = server("PAUSED", { [`/api/v1/runs/${ID}/resume`]: run("QUEUE") });
    renderApp(`/runs/${ID}`);
    expect(await screen.findByRole("button", { name: "Cancel" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Resume" }));
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Resume" }));
    await vi.waitFor(() => expect(requests.some((r) => r.path.endsWith("/resume"))).toBe(true));
  });

  it("retries an ended run from its last checkpoint or from the beginning", async () => {
    const requests = server("FAILED", {
      [`/api/v1/runs/${ID}/checkpoints`]: [COMPLETED],
      [`/api/v1/runs/${ID}/retry`]: run("QUEUE"),
    });
    renderApp(`/runs/${ID}`);
    const retry = await screen.findByRole("button", { name: "Retry" });
    await vi.waitFor(() => expect(retry).toBeEnabled());
    await userEvent.click(retry);
    const dialog = screen.getByRole("dialog", { name: "Retry orders-daily?" });
    expect(within(dialog).getByRole("radio", { name: "Last checkpoint" })).toHaveAttribute("aria-checked", "true");
    await userEvent.click(within(dialog).getByRole("radio", { name: "The beginning" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Retry" }));
    await vi.waitFor(() => expect(requests.some((r) => r.path.endsWith("/retry"))).toBe(true));
    const sent = requests.find((r) => r.path.endsWith("/retry"));
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({ from: "SCRATCH" });
  });

  it("retries from the beginning a run without checkpoints, and offers nothing after success", async () => {
    const requests = server("TIMEOUT", { [`/api/v1/runs/${ID}/retry`]: run("QUEUE") });
    const first = renderApp(`/runs/${ID}`);
    await userEvent.click(await screen.findByRole("button", { name: "Retry" }));
    expect(
      screen.getByText("The run has no completed checkpoint: the next attempt starts from the beginning."),
    ).toBeInTheDocument();
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Retry" }));
    await vi.waitFor(() => expect(requests.some((r) => r.path.endsWith("/retry"))).toBe(true));
    expect(JSON.parse(requests.find((r) => r.path.endsWith("/retry"))?.body ?? "{}")).toEqual({ from: "SCRATCH" });
    first.unmount();

    server("SUCCESS");
    renderApp(`/runs/${ID}`);
    expect(await screen.findByText("Succeeded")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
  });

  it("speaks Chinese", async () => {
    usePreferences.setState({ language: "zh-CN" });
    server("RUNNING");
    renderApp(`/runs/${ID}`);
    await userEvent.click(await screen.findByRole("button", { name: "暂停" }));
    expect(screen.getByRole("dialog", { name: "暂停 orders-daily？" })).toBeInTheDocument();
  });
});
