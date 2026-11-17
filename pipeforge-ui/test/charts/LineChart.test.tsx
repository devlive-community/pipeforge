/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import { LineChart, niceMax } from "@/charts";

const format = (value: number) => String(value);

describe("LineChart", () => {
  it("draws a line per series with its legend and an accessible summary", () => {
    const { container } = render(
      <LineChart
        label="Rates"
        summary="Now 5 rows/s."
        formatX={(ms) => `t${ms}`}
        formatY={format}
        empty="nothing"
        series={[
          {
            id: "a",
            label: "Read",
            color: 1,
            points: [
              { x: 0, y: 1 },
              { x: 10, y: 5 },
            ],
          },
          {
            id: "b",
            label: "Written",
            color: 2,
            points: [
              { x: 0, y: 0 },
              { x: 10, y: 3 },
            ],
          },
        ]}
      />,
    );
    expect(screen.getByRole("img", { name: "Rates. Now 5 rows/s." })).toBeInTheDocument();
    expect(container.querySelectorAll("polyline")).toHaveLength(2);
    expect(screen.getByText("Read")).toBeInTheDocument();
    expect(screen.getByText("t0")).toBeInTheDocument();
    expect(screen.getByText("t10")).toBeInTheDocument();
    // the y axis tops out at a round number above the largest value
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("2.5")).toBeInTheDocument();
  });

  it("explains itself with fewer than two points", () => {
    render(
      <LineChart
        label="Rates"
        summary=""
        formatX={String}
        formatY={format}
        empty="Not enough samples"
        series={[{ id: "a", label: "Read", color: 1, points: [{ x: 0, y: 1 }] }]}
      />,
    );
    expect(screen.getByText("Not enough samples")).toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("rounds the top of the axis", () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(7)).toBe(8);
    expect(niceMax(1800)).toBe(2000);
    expect(niceMax(2300)).toBe(2500);
    expect(niceMax(42_000)).toBe(50_000);
    expect(niceMax(55_000)).toBe(60_000);
  });
});
