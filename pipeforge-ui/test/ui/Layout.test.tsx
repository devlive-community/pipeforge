/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FileButton, Splitter, Table, Tabs } from "@/ui";

describe("Tabs", () => {
  const tabs = [
    { id: "a", label: "Problems", badge: <span>2</span>, content: <p>problem list</p> },
    { id: "b", label: "Plan", content: <p>the plan</p> },
    { id: "c", label: "Text", content: <p>plain text</p> },
  ];

  it("shows one panel and moves with the arrow keys, Home and End", async () => {
    render(<Tabs tabs={tabs} label="Results" />);
    expect(screen.getByRole("tablist", { name: "Results" })).toBeInTheDocument();
    expect(screen.getByRole("tabpanel")).toHaveTextContent("problem list");
    expect(screen.getByRole("tab", { name: "Problems 2" })).toHaveAttribute("aria-selected", "true");
    await userEvent.click(screen.getByRole("tab", { name: "Plan" }));
    expect(screen.getByRole("tabpanel")).toHaveTextContent("the plan");
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "Text" })).toHaveFocus();
    expect(screen.getByRole("tabpanel")).toHaveTextContent("plain text");
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "Problems 2" })).toHaveAttribute("aria-selected", "true");
    await userEvent.keyboard("{ArrowLeft}");
    expect(screen.getByRole("tab", { name: "Text" })).toHaveAttribute("aria-selected", "true");
    await userEvent.keyboard("{Home}");
    expect(screen.getByRole("tab", { name: "Problems 2" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("tab", { name: "Plan" })).toHaveAttribute("tabindex", "-1");
    await userEvent.keyboard("{End}");
    expect(screen.getByRole("tabpanel")).toHaveAttribute(
      "aria-labelledby",
      screen.getByRole("tab", { name: "Text" }).id,
    );
  });

  it("can be controlled", async () => {
    const onChange = vi.fn();
    render(<Tabs tabs={tabs} label="Results" value="b" onChange={onChange} />);
    expect(screen.getByRole("tabpanel")).toHaveTextContent("the plan");
    await userEvent.click(screen.getByRole("tab", { name: "Text" }));
    expect(onChange).toHaveBeenCalledWith("c");
  });
});

describe("Splitter", () => {
  it("moves the divider with the keyboard within 20% and 80%", async () => {
    render(<Splitter first={<p>left</p>} second={<p>right</p>} label="Resize" initial={50} />);
    const divider = screen.getByRole("separator", { name: "Resize" });
    expect(divider).toHaveAttribute("aria-valuenow", "50");
    divider.focus();
    await userEvent.keyboard("{ArrowLeft}");
    expect(divider).toHaveAttribute("aria-valuenow", "45");
    await userEvent.keyboard("{Home}");
    expect(divider).toHaveAttribute("aria-valuenow", "20");
    await userEvent.keyboard("{ArrowLeft}");
    expect(divider).toHaveAttribute("aria-valuenow", "20");
    await userEvent.keyboard("{End}{ArrowRight}");
    expect(divider).toHaveAttribute("aria-valuenow", "80");
    expect(screen.getByText("left").parentElement).toHaveStyle({ flexBasis: "80%" });
  });

  it("follows the pointer while it is captured", () => {
    render(<Splitter first={<p>left</p>} second={<p>right</p>} label="Resize" />);
    const divider = screen.getByRole("separator");
    const container = divider.parentElement as HTMLElement;
    container.getBoundingClientRect = () => ({ left: 0, width: 1000 }) as DOMRect;
    divider.setPointerCapture = vi.fn();
    divider.hasPointerCapture = () => true;
    fireEvent.pointerDown(divider, { pointerId: 1 });
    fireEvent.pointerMove(divider, { pointerId: 1, clientX: 300 });
    expect(divider).toHaveAttribute("aria-valuenow", "30");
    fireEvent.pointerMove(divider, { pointerId: 1, clientX: 50 });
    expect(divider).toHaveAttribute("aria-valuenow", "20");
  });
});

describe("Table", () => {
  it("renders rows, a caption and an empty text", () => {
    const { rerender } = render(
      <Table
        caption="Options"
        rows={[{ key: "rows", value: 10 }]}
        rowKey={(r) => r.key}
        columns={[
          { key: "k", header: "Option", cell: (r) => r.key },
          { key: "v", header: "Value", cell: (r) => r.value },
        ]}
      />,
    );
    expect(screen.getByRole("table", { name: "Options" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Option" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "10" })).toBeInTheDocument();
    rerender(<Table caption="Options" rows={[]} rowKey={() => ""} columns={[]} empty="No options" />);
    expect(screen.getByText("No options")).toBeInTheDocument();
  });
});

describe("FileButton", () => {
  it("hands over the chosen file", async () => {
    const onFile = vi.fn();
    render(<FileButton onFile={onFile}>Import</FileButton>);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const click = vi.spyOn(input, "click");
    await userEvent.click(screen.getByRole("button", { name: "Import" }));
    expect(click).toHaveBeenCalled();
    await userEvent.upload(input, new File(["version: 1"], "job.yaml", { type: "application/yaml" }));
    expect(onFile).toHaveBeenCalledWith("job.yaml", "version: 1");
  });
});
