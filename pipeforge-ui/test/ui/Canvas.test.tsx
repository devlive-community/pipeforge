/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { edgePath, layout, NODE_WIDTH } from "@/canvas/layout";
import { DagCanvas, type DagNode } from "@/ui";

describe("layout", () => {
  it("puts nodes in layers after their inputs, keeping the written order", () => {
    const { placed, width, height } = layout([
      { id: "a", inputs: [] },
      { id: "b", inputs: [] },
      { id: "t", inputs: ["a", "b"] },
      { id: "x", inputs: ["t"] },
      { id: "y", inputs: ["a"] },
    ]);
    expect(placed.map((p) => [p.id, p.layer])).toEqual([
      ["a", 0],
      ["b", 0],
      ["t", 1],
      ["x", 2],
      ["y", 1],
    ]);
    expect(placed.find((p) => p.id === "y")?.y).toBeGreaterThan(0);
    expect(width).toBe(placed.find((p) => p.id === "x")!.x + NODE_WIDTH);
    expect(height).toBeGreaterThan(0);
  });

  it("survives cycles, self references and unknown inputs", () => {
    const { placed } = layout([
      { id: "a", inputs: ["b", "nope"] },
      { id: "b", inputs: ["a"] },
      { id: "c", inputs: ["c"] },
    ]);
    expect(placed).toHaveLength(3);
    expect(placed.find((p) => p.id === "c")?.layer).toBe(0);
    expect(layout([])).toEqual({ placed: [], width: 0, height: 0 });
  });

  it("draws edges from the right side to the left side", () => {
    const path = edgePath({ id: "a", x: 0, y: 0, layer: 0 }, { id: "b", x: 300, y: 84, layer: 1 });
    expect(path).toMatch(/^M176,28 C/);
    expect(path).toMatch(/ 300,112$/);
  });
});

describe("DagCanvas", () => {
  const nodes: DagNode[] = [
    { id: "src", kind: "source", detail: "fake", inputs: [], label: "src, source fake" },
    {
      id: "out",
      kind: "sink",
      detail: "console",
      inputs: ["src"],
      status: "error",
      label: "out, sink console, has a problem",
    },
  ];
  const labels = { zoomIn: "Zoom in", zoomOut: "Zoom out", fit: "Reset the view" };

  it("shows nodes as buttons and edges", async () => {
    const onSelect = vi.fn();
    render(<DagCanvas nodes={nodes} label="Graph" labels={labels} onSelect={onSelect} />);
    expect(screen.getByRole("group", { name: "Graph" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "out, sink console, has a problem" }));
    expect(onSelect).toHaveBeenCalledWith("out");
    expect(screen.getByRole("button", { name: "out, sink console, has a problem" }).className).toContain(
      "border-danger",
    );
    expect(document.querySelectorAll("svg path[d^='M176']")).toHaveLength(1);
  });

  it("zooms, pans and resets", async () => {
    render(<DagCanvas nodes={nodes} label="Graph" labels={labels} />);
    const layer = screen.getByRole("group", { name: "Graph" }).firstElementChild as HTMLElement;
    const before = layer.style.transform;
    await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));
    expect(layer.style.transform).toContain("scale(1.2)");
    await userEvent.click(screen.getByRole("button", { name: "Zoom out" }));
    await userEvent.click(screen.getByRole("button", { name: "Zoom out" }));
    expect(layer.style.transform).toMatch(/scale\(0\.83/);
    await userEvent.click(screen.getByRole("button", { name: "Reset the view" }));
    expect(layer.style.transform).toBe(before);
    const group = screen.getByRole("group", { name: "Graph" });
    group.dispatchEvent(new WheelEvent("wheel", { deltaX: 10, deltaY: 20, bubbles: true }));
    await screen.findByRole("group");
    expect(layer.style.transform).not.toBe(before);
  });

  it("shows metrics under nodes, marks one and sizes the edges", () => {
    const nodes: DagNode[] = [
      { id: "a", kind: "source", detail: "fake", inputs: [], label: "a", metric: "10 rows" },
      { id: "b", kind: "sink", detail: "jdbc", inputs: ["a"], label: "b", emphasis: true, selected: true },
    ];
    const { container } = render(
      <DagCanvas
        nodes={nodes}
        label="Graph"
        labels={{ zoomIn: "In", zoomOut: "Out", fit: "Fit" }}
        onSelect={() => {}}
        edgeWidth={() => 20}
      />,
    );
    expect(screen.getByText("10 rows")).toBeInTheDocument();
    const marked = screen.getByRole("button", { name: "b" });
    expect(marked).toHaveClass("border-warning");
    expect(marked).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "a" })).not.toHaveAttribute("aria-pressed");
    // widths are kept between 1 and 8
    expect(container.querySelector("path")).toHaveAttribute("stroke-width", "8");
  });
});
