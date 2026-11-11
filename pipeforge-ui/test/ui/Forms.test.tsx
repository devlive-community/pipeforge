/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { Input, OptionForm, SegmentedControl, type OptionFormLabels } from "@/ui";

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
