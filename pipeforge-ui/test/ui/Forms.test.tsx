/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { Input, OptionForm, parseOptionValue, SegmentedControl, type OptionField, type OptionFormLabels } from "@/ui";

const LABELS: OptionFormLabels = {
  requirement: { required: "required", optional: "optional" },
  defaultValue: "Default",
  range: "Range",
  values: "Values",
  sensitive: "secret",
  deprecated: "Old names",
  since: "Since",
};

describe("Input", () => {
  it("is labelled and announces its error", async () => {
    const onChange = vi.fn();
    render(<Input label="Name" error="Name is required" onChange={onChange} />);
    const input = screen.getByRole("textbox", { name: "Name" });
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("Name is required");
    await userEvent.type(input, "ab");
    expect(onChange).toHaveBeenCalledTimes(2);
  });

  it("keeps a hidden label for assistive technology", () => {
    render(<Input label="Search" hideLabel icon="search" />);
    expect(screen.getByRole("textbox", { name: "Search" })).not.toHaveAttribute("aria-invalid");
    expect(screen.getByText("Search")).toHaveClass("sr-only");
  });
});

describe("SegmentedControl", () => {
  function Segments() {
    const [value, setValue] = useState("all");
    return (
      <SegmentedControl
        label="Category"
        value={value}
        onChange={setValue}
        segments={[
          { id: "all", label: "All" },
          { id: "string", label: "String" },
          { id: "numeric", label: "Numeric" },
        ]}
      />
    );
  }

  it("selects by click and moves with the arrow keys, wrapping around", async () => {
    render(<Segments />);
    expect(screen.getByRole("radiogroup", { name: "Category" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "All" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "String" })).toHaveAttribute("tabindex", "-1");

    await userEvent.click(screen.getByRole("radio", { name: "Numeric" }));
    expect(screen.getByRole("radio", { name: "Numeric" })).toBeChecked();
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByRole("radio", { name: "All" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "All" })).toHaveFocus();
    await userEvent.keyboard("{ArrowLeft}");
    expect(screen.getByRole("radio", { name: "Numeric" })).toBeChecked();
  });
});

describe("OptionForm", () => {
  it("shows every rule of an option", () => {
    render(
      <OptionForm
        labels={LABELS}
        empty="No options"
        options={[
          {
            key: "rows",
            type: "LONG",
            requirement: "optional",
            defaultValue: 1000,
            min: 0,
            max: 100,
            description: "Rows to produce",
            deprecatedKeys: ["row.count"],
            since: "0.1.0",
          },
          { key: "password", type: "STRING", requirement: "required", sensitive: true, values: ["a", "b"] },
        ]}
      />,
    );
    expect(screen.getByText("rows")).toBeInTheDocument();
    expect(screen.getByText("Rows to produce")).toBeInTheDocument();
    expect(screen.getByText("1000")).toBeInTheDocument();
    expect(screen.getByText("0 – 100")).toBeInTheDocument();
    expect(screen.getByText("row.count")).toBeInTheDocument();
    expect(screen.getByText("required")).toBeInTheDocument();
    expect(screen.getByText("secret")).toBeInTheDocument();
    expect(screen.getByText("a, b")).toBeInTheDocument();
  });

  it("says when there are no options", () => {
    render(<OptionForm labels={LABELS} empty="No options" options={[]} />);
    expect(screen.getByText("No options")).toBeInTheDocument();
  });
});

const EDITING: OptionFormLabels = {
  ...LABELS,
  editing: {
    field: "Value of {key}",
    unset: "default",
    editInText: "Edit in the text",
    notANumber: "Not a number",
    notAnInteger: "Not a whole number",
    outOfRange: "Outside {min} – {max}",
  },
};

const EDITABLE: OptionField[] = [
  { key: "rows", type: "long", requirement: "optional", defaultValue: 1000, min: 0, max: 5000 },
  { key: "verbose", type: "boolean", requirement: "optional" },
  { key: "format", type: "enum", requirement: "optional", values: ["table", "json"] },
  { key: "schema", type: "list", requirement: "required" },
  { key: "password", type: "string", requirement: "optional", sensitive: true },
];

describe("OptionForm editing", () => {
  function Editable({ onChange }: { onChange: (key: string, value: unknown) => void }) {
    const [values, setValues] = useState<Record<string, unknown>>({ rows: 10, schema: ["id INT64"] });
    return (
      <OptionForm
        options={EDITABLE}
        labels={EDITING}
        empty="No options"
        values={values}
        onChange={(key, value) => {
          onChange(key, value);
          setValues({ ...values, [key]: value });
        }}
      />
    );
  }

  it("edits each option with a control for its type", async () => {
    const changes: [string, unknown][] = [];
    render(<Editable onChange={(key, value) => changes.push([key, value])} />);
    const rows = screen.getByRole("textbox", { name: "Value of rows" });
    expect(rows).toHaveValue("10");
    expect(rows).toHaveAttribute("placeholder", "1000");
    await userEvent.clear(rows);
    await userEvent.type(rows, "250{Enter}");
    await userEvent.click(screen.getByRole("radio", { name: "true" }));
    await userEvent.click(screen.getByRole("button", { name: "Value of format" }));
    await userEvent.click(screen.getByRole("menuitemradio", { name: "json" }));
    expect(changes).toEqual([
      ["rows", 250],
      ["verbose", true],
      ["format", "json"],
    ]);
    // lists and maps are shown, not edited
    expect(screen.getByText('["id INT64"]')).toBeInTheDocument();
    expect(screen.getByText("Edit in the text")).toBeInTheDocument();
    // a secret is not shown as typed
    expect(screen.getByLabelText("Value of password")).toHaveAttribute("type", "password");
  });

  it("refuses values that are no value of the option, and removes an emptied one", async () => {
    const changes: [string, unknown][] = [];
    render(<Editable onChange={(key, value) => changes.push([key, value])} />);
    const rows = screen.getByRole("textbox", { name: "Value of rows" });
    await userEvent.clear(rows);
    await userEvent.type(rows, "9000{Enter}");
    expect(screen.getByText("Outside 0 – 5000")).toBeInTheDocument();
    expect(rows).toHaveAttribute("aria-invalid", "true");
    await userEvent.clear(rows);
    await userEvent.tab();
    expect(changes).toEqual([["rows", undefined]]);
  });

  it("parses typed values", () => {
    expect(parseOptionValue({ type: "int" }, " 42 ")).toEqual({ value: 42 });
    expect(parseOptionValue({ type: "int" }, "4.5")).toEqual({ error: "notAnInteger" });
    expect(parseOptionValue({ type: "double" }, "4.5")).toEqual({ value: 4.5 });
    expect(parseOptionValue({ type: "long" }, "many")).toEqual({ error: "notANumber" });
    expect(parseOptionValue({ type: "long" }, "${env:ROWS}")).toEqual({ value: "${env:ROWS}" });
    expect(parseOptionValue({ type: "duration" }, "30s")).toEqual({ value: "30s" });
    expect(parseOptionValue({ type: "string" }, "")).toEqual({ value: undefined });
  });

  it("stays read-only without onChange", () => {
    render(<OptionForm options={EDITABLE} labels={EDITING} empty="No options" />);
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("radio")).toBeNull();
  });
});
