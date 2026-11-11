/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { fireEvent, render, screen } from "@testing-library/react";
import { EDGES, NODES, lineage } from "@/components/home/dag";
import { FeatureGrid } from "@/components/home/FeatureGrid";
import { HeroDag } from "@/components/home/HeroDag";

describe("home page DAG", () => {
  it("computes the upstream and downstream lineage of a node", () => {
    expect([...lineage("mysql")].sort()).toEqual(["doris", "iceberg", "mapper", "mysql"]);
    expect([...lineage("doris")].sort()).toEqual(["doris", "kafka", "mapper", "mysql"]);
    expect(lineage("mapper").size).toBe(NODES.length);
  });

  it("connects every edge to existing nodes", () => {
    const ids = new Set(NODES.map((n) => n.id));
    for (const edge of EDGES) {
      expect(ids.has(edge.from) && ids.has(edge.to)).toBe(true);
      expect(edge.path).toMatch(/^M [\d.]+ [\d.]+ C /);
    }
  });

  it("dims nodes outside the lineage of the hovered node", () => {
    render(<HeroDag label="Source to sink" />);
    expect(screen.getByRole("img", { name: "Source to sink" })).toBeInTheDocument();
    fireEvent.mouseEnter(screen.getByText("Doris"));
    expect(screen.getByText("Doris").className).toContain("opacity-100");
    expect(screen.getByText("Iceberg").className).toContain("opacity-40");
    fireEvent.mouseLeave(screen.getByText("Doris"));
    expect(screen.getByText("Iceberg").className).toContain("opacity-100");
  });

  it("reveals feature cards one after another", () => {
    render(
      <FeatureGrid
        features={[
          ["A", "a"],
          ["B", "b"],
        ]}
      />,
    );
    const cards = screen.getAllByRole("listitem");
    // Without IntersectionObserver (jsdom) the cards are shown immediately.
    expect(cards[1]).toHaveClass("animate-fade-up");
    expect(cards[1]).toHaveStyle({ animationDelay: "60ms" });
  });
});
