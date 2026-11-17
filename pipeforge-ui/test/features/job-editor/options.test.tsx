/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EXAMPLE_JOB } from "@/features/job-editor/example";
import { DRAFT_STORAGE_KEY } from "@/features/job-editor/useDraft";
import { readNodeOptions, setNodeOption } from "@/features/field-mapping/yamlEdit";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp, SERVER } from "../../render";
import { JOB_SCHEMA } from "./schema-fixture";

const JOB = `version: 1
name: demo
source:
  - id: orders
    type: fake
    # how many rows to make
    rows: 10
    table: demo.orders
sink:
  - { id: print, type: console }
`;

describe("writing node options", () => {
  it("sets, replaces and removes options, keeping the rest as written", () => {
    let text = setNodeOption(JOB, "orders", "rows", 250);
    expect(text).toContain("    # how many rows to make\n    rows: 250\n");
    text = setNodeOption(text, "orders", "verbose", true);
    expect(text).toContain("    table: demo.orders\n    verbose: true\n");
    text = setNodeOption(text, "orders", "rows", undefined);
    expect(text).not.toContain("rows:");
    // a string that looks like a number stays a string
    expect(setNodeOption(JOB, "orders", "table", "100")).toContain('table: "100"');
    // a flow node is rewritten in block style first
    expect(setNodeOption(JOB, "print", "format", "json")).toContain("format: json");
    expect(setNodeOption(JOB, "nope", "rows", 1)).toBe(JOB);
  });

  it("reads the options a node sets", () => {
    expect(readNodeOptions(JOB, "orders", ["rows", "table", "rate"])).toEqual({ rows: 10, table: "demo.orders" });
    expect(readNodeOptions("not: [valid", "orders", ["rows"])).toEqual({});
  });
});

const FAKE = {
  type: "source",
  identifier: "fake",
  plugin: "fake@0.1.0",
  options: [
    { key: "rows", type: "long", requirement: "one-of", defaultValue: 1000, min: 0 },
    { key: "table", type: "string", requirement: "required" },
  ],
};

describe("job editor options", () => {
  beforeEach(() => {
    usePreferences.setState({ language: "en-US", theme: "system" });
    localStorage.removeItem(DRAFT_STORAGE_KEY);
  });

  it("edits the options of a node chosen in the graph into the definition", async () => {
    localStorage.setItem(DRAFT_STORAGE_KEY, EXAMPLE_JOB.replace("    type: fake\n", "    type: fake\n    rows: 10\n"));
    fakeServer({
      ...SERVER,
      "/api/v1/jobs/schema": JOB_SCHEMA,
      "/api/v1/jobs/validate": { valid: true, errors: [], warnings: [] },
      "/api/v1/explain": { plan: { nodes: [{ id: "orders", plugin: "fake@0.1.0" }], warnings: [] }, text: "" },
      "/api/v1/components/source/fake": FAKE,
    });
    renderApp("/editor");
    await screen.findByText("Valid", {}, { timeout: 3000 });
    await userEvent.click(screen.getByRole("radio", { name: "Topology" }));
    await userEvent.click(screen.getByRole("button", { name: /^orders,/ }));
    await userEvent.click(screen.getByRole("tab", { name: "Options" }));
    const rows = await screen.findByRole("textbox", { name: "Value of rows" });
    expect(rows).toHaveValue("10");
    await userEvent.clear(rows);
    await userEvent.type(rows, "500{Enter}");
    await waitFor(() => expect(screen.getByTestId("code-editor")).toHaveTextContent("rows: 500"));
    await waitFor(() => expect(localStorage.getItem(DRAFT_STORAGE_KEY)).toContain("    rows: 500\n"));
  });

  it("says when the component of a node is not installed", async () => {
    localStorage.setItem(DRAFT_STORAGE_KEY, EXAMPLE_JOB.replace("type: console", "type: kafka"));
    fakeServer({
      ...SERVER,
      "/api/v1/jobs/schema": JOB_SCHEMA,
      "/api/v1/jobs/validate": { valid: false, errors: [], warnings: [] },
    });
    renderApp("/editor");
    await screen.findByRole("heading", { level: 1, name: "Job editor" });
    await userEvent.click(screen.getByRole("tab", { name: "Options" }));
    await userEvent.click(screen.getByRole("button", { name: "Node" }));
    await userEvent.click(screen.getByRole("menuitemradio", { name: "print (kafka)" }));
    expect(
      await screen.findByText("There is no installed component of type kafka, so its options are unknown."),
    ).toBeInTheDocument();
  });
});
