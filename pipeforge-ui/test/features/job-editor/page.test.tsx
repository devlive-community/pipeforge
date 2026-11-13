/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EXAMPLE_JOB } from "@/features/job-editor/example";
import { DRAFT_STORAGE_KEY } from "@/features/job-editor/useDraft";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp, SERVER } from "../../render";
import { JOB_SCHEMA } from "./schema-fixture";

const PLAN = {
  plan: {
    job: {
      name: "orders-daily",
      engine: "native",
      mode: "batch",
      modeDerived: true,
      delivery: "at-least-once",
      parallelism: 2,
      timezone: "UTC",
    },
    variables: { biz_date: "2026-11-10" },
    nodes: [
      {
        id: "orders",
        kind: "source",
        type: "fake",
        plugin: "fake@0.1.0",
        inputs: [],
        inputsImplied: false,
        parallelism: 2,
        boundedness: "bounded",
        options: [
          { key: "rows", value: 100, origin: "node", sensitive: false },
          { key: "fetch-size", value: 500, origin: "env", sensitive: false },
          { key: "limit", value: 20, origin: "default", sensitive: false },
        ],
        inputTables: [],
        outputTables: [
          {
            id: "demo.orders",
            columns: [{ name: "id", type: "INT64", nullable: false }],
            primaryKey: ["id"],
            partitionKeys: [],
          },
        ],
      },
      {
        id: "print",
        kind: "sink",
        type: "console",
        plugin: "console@0.1.0",
        inputs: ["orders"],
        inputsImplied: true,
        parallelism: 2,
        options: [],
        inputTables: [
          {
            id: "demo.orders",
            columns: [{ name: "id", type: "INT64", nullable: false }],
            primaryKey: [],
            partitionKeys: [],
          },
        ],
        outputTables: [],
      },
    ],
    warnings: ["env.sink.batch-size is not an option of any sink in the job"],
  },
  text: "Job orders-daily  mode=batch (derived)",
};

/** A server where a definition containing "kafka" fails at the node print; anything else is valid. */
function server() {
  return fakeServer({
    ...SERVER,
    "/api/v1/jobs/schema": JOB_SCHEMA,
    "/api/v1/jobs/validate": (body: string) =>
      body.includes("kafka")
        ? {
            valid: false,
            errors: [
              { code: "PF-PLUG-0001", message: "The sink plugin 'kafka' is not installed", scope: { node: "print" } },
            ],
            warnings: [],
          }
        : { valid: true, errors: [], warnings: PLAN.plan.warnings },
    "/api/v1/explain": PLAN,
  });
}

async function openEditor() {
  renderApp("/editor");
  return screen.findByRole("heading", { level: 1, name: "Job editor" });
}

describe("JobEditorPage", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("starts with the example, validates it and shows the plan", async () => {
    const requests = server();
    await openEditor();
    // the editor mounts after the page heading
    await waitFor(() => expect(screen.getByTestId("code-editor")).toHaveTextContent("name: orders-daily"));
    expect(await screen.findByText("Valid", {}, { timeout: 3000 })).toBeInTheDocument();
    expect(requests.some((r) => r.path === "/api/v1/jobs/validate")).toBe(true);
    expect(requests.some((r) => r.path === "/api/v1/jobs/schema")).toBe(true);
    const problems = screen.getByRole("tabpanel");
    expect(
      within(problems).getByText("env.sink.batch-size is not an option of any sink in the job"),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "Plan" }));
    const plan = await screen.findByRole("table", { name: "Nodes" });
    expect(within(plan).getByText("fake@0.1.0")).toBeInTheDocument();
    expect(within(plan).getByText("orders (implied)")).toBeInTheDocument();
    const options = screen.getByRole("table", { name: "Options of node orders" });
    expect(within(options).getByText("fetch-size")).toBeInTheDocument();
    expect(within(options).queryByText("limit")).toBeNull();
    expect(screen.getByRole("table", { name: "orders produces demo.orders" })).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "print receives demo.orders" })).toBeInTheDocument();
    expect(screen.getByText("batch (derived from the sources)")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "Text" }));
    expect(
      screen.getByText("Job orders-daily mode=batch (derived)", { normalizer: (s) => s.replace(/\s+/g, " ") }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "orders, source fake" })).toBeInTheDocument();
  });

  it("marks problems at their node and keeps the draft", async () => {
    server();
    localStorage.setItem(DRAFT_STORAGE_KEY, EXAMPLE_JOB.replace("type: console", "type: kafka"));
    await openEditor();
    expect(await screen.findByText("1 problem(s)", {}, { timeout: 3000 })).toBeInTheDocument();
    expect(screen.getByText("The sink plugin 'kafka' is not installed")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Line 15" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "print, sink kafka, has a problem" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("tab", { name: "Plan" }));
    expect(screen.getByText("The plan appears when the job is valid.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Load the example" }));
    expect(await screen.findByText("Valid", {}, { timeout: 3000 })).toBeInTheDocument();
    await act(() => new Promise((r) => setTimeout(r, 350)));
    expect(localStorage.getItem(DRAFT_STORAGE_KEY)).toBe(EXAMPLE_JOB);
  });

  it("imports a file and explains when the server cannot check", async () => {
    server();
    await openEditor();
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    await userEvent.upload(input, new File(["version: 1\nname: imported\n"], "imported.yaml"));
    await waitFor(() => expect(screen.getByTestId("code-editor")).toHaveTextContent("name: imported"));

    fakeServer({ ...SERVER, "/api/v1/jobs/validate": [503, { code: "PF-SYS-0001", message: "down" }] });
    await userEvent.upload(input, new File(["version: 1\nname: another\n"], "another.yaml"));
    expect(await screen.findByText("The job cannot be checked: down", {}, { timeout: 3000 })).toBeInTheDocument();
  });

  it("asks for a job when the definition is empty", async () => {
    server();
    localStorage.setItem(DRAFT_STORAGE_KEY, "");
    await openEditor();
    expect(screen.getByText("Write a job to check it.")).toBeInTheDocument();
  });
});
