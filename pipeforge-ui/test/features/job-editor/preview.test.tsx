/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EXAMPLE_JOB } from "@/features/job-editor/example";
import { cellText } from "@/features/job-editor/PreviewPanel";
import { DRAFT_STORAGE_KEY } from "@/features/job-editor/useDraft";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp, SERVER, type Recorded } from "../../render";
import { JOB_SCHEMA } from "./schema-fixture";

const RESULT = {
  node: "rename",
  outcome: "LIMIT",
  elapsedMs: 12,
  rowsRead: 3,
  tables: [
    {
      table: "demo.orders",
      columns: [
        { name: "id", type: "INT64" },
        { name: "note", type: "STRING" },
        { name: "tags", type: "ARRAY<STRING>" },
      ],
      rows: [
        [1, "first", ["a", "b"]],
        [2, null, []],
      ],
      truncated: true,
    },
  ],
};

function server(preview: unknown = RESULT) {
  const requests: Recorded[] = fakeServer({
    ...SERVER,
    "/api/v1/jobs/schema": JOB_SCHEMA,
    "/api/v1/jobs/validate": (body: string) =>
      body.includes("kafka")
        ? { valid: false, errors: [{ code: "PF-PLUG-0001", message: "not installed" }], warnings: [] }
        : { valid: true, errors: [], warnings: [] },
    "/api/v1/explain": { plan: { nodes: [], warnings: [] }, text: "" },
    "/api/v1/jobs/preview": () => preview,
  });
  return requests;
}

const previews = (requests: Recorded[]) =>
  requests
    .filter((r) => r.path === "/api/v1/jobs/preview")
    .map((r) => JSON.parse(r.body) as { definition: string; node?: string });

async function openEditor() {
  renderApp("/editor");
  await screen.findByRole("heading", { level: 1, name: /^Job editor/ });
  expect(await screen.findByText("Valid", {}, { timeout: 3000 })).toBeInTheDocument();
}

describe("job editor preview", () => {
  beforeEach(() => {
    usePreferences.setState({ language: "en-US", theme: "system" });
    localStorage.removeItem(DRAFT_STORAGE_KEY);
  });

  it("dry-runs the job from the toolbar and shows the rows", async () => {
    const requests = server();
    await openEditor();
    await userEvent.click(screen.getByRole("button", { name: "Dry run" }));
    const table = await screen.findByRole("table", { name: "Rows of demo.orders" });
    expect(previews(requests)).toEqual([{ definition: EXAMPLE_JOB }]);
    expect(screen.getByRole("tab", { name: "Preview", selected: true })).toBeInTheDocument();
    expect(screen.getByText("Stopped at the row limit")).toBeInTheDocument();
    expect(screen.getByText("3 rows read in 12 ms")).toBeInTheDocument();
    expect(within(table).getByText("ARRAY<STRING>")).toBeInTheDocument();
    expect(within(table).getByText('["a","b"]')).toBeInTheDocument();
    expect(within(table).getByText("NULL")).toBeInTheDocument();
    expect(screen.getByText("The first 2 rows.")).toBeInTheDocument();
  });

  it("previews a chosen node, also when chosen in the graph", async () => {
    const requests = server();
    await openEditor();
    await userEvent.click(screen.getByRole("tab", { name: "Preview" }));
    expect(screen.getByText(/Run a dry run to see the rows/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "What to preview" }));
    const menu = screen.getByRole("menu");
    // sinks write; only sources and transforms can be previewed
    expect(within(menu).queryByRole("menuitemradio", { name: /print/ })).toBeNull();
    await userEvent.click(within(menu).getByRole("menuitemradio", { name: "orders (fake)" }));
    await userEvent.click(screen.getByRole("button", { name: "Run" }));
    await screen.findByRole("table", { name: "Rows of demo.orders" });
    expect(previews(requests).at(-1)?.node).toBe("orders");

    await userEvent.click(screen.getByRole("radio", { name: "Topology" }));
    await userEvent.click(screen.getByRole("button", { name: /^rename,/ }));
    await userEvent.click(screen.getByRole("tab", { name: "Preview" }));
    expect(screen.getByRole("button", { name: "What to preview" })).toHaveTextContent("rename (field-mapper)");
    expect(screen.queryByRole("table", { name: "Rows of demo.orders" })).not.toBeInTheDocument();
  });

  it("reports a failed preview and a server that cannot run one", async () => {
    server({
      node: "orders",
      outcome: "FAILED",
      elapsedMs: 40,
      rowsRead: 0,
      tables: [],
      error: { code: "PF-CONN-0003", message: "Cannot connect to localhost:3306" },
    });
    await openEditor();
    await userEvent.click(screen.getByRole("button", { name: "Dry run" }));
    expect(await screen.findByText("Cannot connect to localhost:3306")).toBeInTheDocument();
    expect(screen.getByText("PF-CONN-0003")).toBeInTheDocument();
    expect(screen.getByText("Failed")).toBeInTheDocument();
  });

  it("says when the server refuses the preview", async () => {
    server([503, { code: "PF-CONF-0007", message: "Too many previews" }]);
    await openEditor();
    await userEvent.click(screen.getByRole("button", { name: "Dry run" }));
    expect(await screen.findByText("The preview cannot run: Too many previews")).toBeInTheDocument();
  });

  it("marks a preview of an earlier version of the job", async () => {
    localStorage.setItem(DRAFT_STORAGE_KEY, EXAMPLE_JOB.replace("orders-daily", "orders-hourly"));
    server();
    await openEditor();
    await userEvent.click(screen.getByRole("button", { name: "Dry run" }));
    await screen.findByRole("table", { name: "Rows of demo.orders" });
    expect(screen.queryByText(/The job changed since this preview/)).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Load the example" }));
    expect(await screen.findByText(/The job changed since this preview/)).toBeInTheDocument();
  });

  it("cannot run a job with problems", async () => {
    localStorage.setItem(DRAFT_STORAGE_KEY, EXAMPLE_JOB.replace("type: console", "type: kafka"));
    server();
    renderApp("/editor");
    expect(await screen.findByText("1 problem(s)", {}, { timeout: 3000 })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Dry run" })).toBeDisabled();
  });

  it("writes cells as text", () => {
    expect(cellText(null)).toBe("NULL");
    expect(cellText({ a: 1 })).toBe('{"a":1}');
    expect(cellText(1.5)).toBe("1.5");
  });
});
