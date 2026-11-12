/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { parse } from "yaml";
import { MappingEditor } from "@/features/field-mapping/MappingEditor";
import { usePreferences } from "@/state/preferences";

export const MAPPED_JOB = `version: 1
name: orders
source:
  - { id: src, type: fake }
transform:
  - id: map            # tidy the columns
    type: field-mapper
    mapping:
      - { expr: "UPPER(name)", to: loud }
sink:
  - { id: out, type: console }
`;

export const MAPPER_NODE = {
  id: "map",
  kind: "transform",
  type: "field-mapper",
  inputTables: [
    {
      id: "fake.rows",
      columns: [
        { name: "id", type: "INT64", nullable: false },
        { name: "name", type: "STRING(32)", nullable: true },
        { name: "amount", type: "DECIMAL(10, 2)", nullable: true },
      ],
      primaryKey: ["id"],
    },
  ],
  outputTables: [
    {
      id: "fake.rows",
      columns: [
        { name: "id", type: "INT64", nullable: false },
        { name: "name", type: "STRING(32)", nullable: true },
        { name: "amount", type: "DECIMAL(10, 2)", nullable: true },
        { name: "loud", type: "STRING", nullable: true },
      ],
      primaryKey: ["id"],
    },
  ],
};

/** The editor with its own copy of the job, as the job editor holds it; returns every version written. */
function setup(node: typeof MAPPER_NODE | null = MAPPER_NODE) {
  const written: string[] = [];
  function Host() {
    const [source, setSource] = useState(MAPPED_JOB);
    return (
      <MappingEditor
        source={source}
        nodeId="map"
        node={(node ?? undefined) as never}
        stale={false}
        onChange={(next) => {
          written.push(next);
          setSource(next);
        }}
      />
    );
  }
  render(<Host />);
  return written;
}

const mapper = (yaml: string) => parse(yaml).transform[0];

describe("MappingEditor", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("shows the input and output columns with where each value comes from", () => {
    setup();
    const inputs = screen.getByRole("region", { name: "Input columns" });
    expect(
      within(inputs)
        .getAllByRole("listitem")
        .map((i) => i.textContent),
    ).toEqual([expect.stringContaining("id"), expect.stringContaining("name"), expect.stringContaining("amount")]);
    const outputs = screen.getByRole("region", { name: "Output columns" });
    expect(within(outputs).getByRole("button", { name: "loud, STRING" })).toHaveTextContent("Expression");
    expect(within(outputs).getByRole("button", { name: "amount, DECIMAL(10, 2)" })).not.toHaveTextContent("Expression");
    expect(screen.getByText("Choose an output column to edit it.")).toBeInTheDocument();
  });

  it("renames a kept column into an entry and keeps the comment of the node", async () => {
    const written = setup();
    await userEvent.click(screen.getByRole("button", { name: "name, STRING(32)" }));
    const details = screen.getByRole("region", { name: "Column name" });
    const nameInput = within(details).getByRole("textbox", { name: "Name" });
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, "customer{Enter}");
    const last = written.at(-1) ?? "";
    expect(mapper(last).mapping).toEqual([
      { expr: "UPPER(name)", to: "loud" },
      { from: "name", to: "customer" },
    ]);
    expect(last).toContain("  - id: map            # tidy the columns");
    expect(screen.getByRole("region", { name: "Column customer" })).toBeInTheDocument();
  });

  it("changes the type, the source and the nullability of an entry", async () => {
    const written = setup();
    await userEvent.click(screen.getByRole("button", { name: "loud, STRING" }));
    await userEvent.click(screen.getByRole("radio", { name: "Column" }));
    expect(mapper(written.at(-1) ?? "").mapping[0]).toEqual({ from: "id", to: "loud" });

    await userEvent.click(screen.getByRole("button", { name: "id" }));
    await userEvent.click(screen.getByRole("menuitemradio", { name: "amount" }));
    expect(mapper(written.at(-1) ?? "").mapping[0]).toEqual({ from: "amount", to: "loud" });

    await userEvent.type(screen.getByRole("textbox", { name: "Type" }), "INT32{Enter}");
    expect(mapper(written.at(-1) ?? "").mapping[0]).toEqual({ from: "amount", to: "loud", type: "INT32" });
    expect(screen.getByRole("region", { name: "Column loud" })).toHaveTextContent("may lose data");

    await userEvent.click(screen.getByRole("radio", { name: "NOT NULL" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Default" }), "0{Enter}");
    expect(mapper(written.at(-1) ?? "").mapping[0]).toMatchObject({ nullable: false, default: "0" });

    await userEvent.click(screen.getByRole("button", { name: "Remove the column" }));
    expect(mapper(written.at(-1) ?? "").mapping).toBeUndefined();
  });

  it("drops and restores input columns and sets the node options", async () => {
    const written = setup();
    await userEvent.click(screen.getByRole("button", { name: "Drop amount" }));
    expect(mapper(written.at(-1) ?? "").drop).toEqual(["amount"]);
    await userEvent.click(screen.getByRole("button", { name: "Keep amount" }));
    expect(mapper(written.at(-1) ?? "").drop).toBeUndefined();

    await userEvent.click(screen.getByRole("radio", { name: "snake_case" }));
    await userEvent.click(screen.getByRole("radio", { name: "Mapping order" }));
    await userEvent.click(
      within(screen.getByRole("radiogroup", { name: "Other columns" })).getByRole("radio", { name: "Drop" }),
    );
    expect(mapper(written.at(-1) ?? "")).toMatchObject({ "name-case": "snake", order: "mapping", others: "drop" });
    await userEvent.click(screen.getByRole("radio", { name: "As written" }));
    expect(mapper(written.at(-1) ?? "")["name-case"]).toBeUndefined();

    await userEvent.click(screen.getByRole("button", { name: "Add a column" }));
    expect(mapper(written.at(-1) ?? "").mapping.at(-1)).toEqual({ value: "", to: "new_column" });
    expect(screen.getByRole("region", { name: "Column new_column" })).toBeInTheDocument();
  });

  it("explains that it needs the plan", () => {
    setup(null);
    expect(
      screen.getByText("The mapping editor needs the plan: it appears once the job is valid."),
    ).toBeInTheDocument();
  });
});
