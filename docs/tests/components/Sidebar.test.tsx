/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Sidebar } from "@/components/layout/Sidebar";

const sections = [
  {
    key: "getting-started",
    items: [{ id: "getting-started/overview", title: "Overview", url: "/en-US/next/getting-started/overview/" }],
  },
  {
    key: "development",
    items: [
      { id: "development/contributing", title: "Contributing", url: "/en-US/next/development/contributing/" },
      { id: "development/docs-guide", title: "Writing documentation", url: "/en-US/next/development/docs-guide/" },
    ],
  },
];
const labels = { "getting-started": "Getting started", development: "Development" };

describe("Sidebar", () => {
  it("marks the current page", () => {
    render(<Sidebar sections={sections} currentId="development/docs-guide" labels={labels} />);
    expect(screen.getByRole("link", { name: "Writing documentation" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Contributing" })).not.toHaveAttribute("aria-current");
  });

  it("collapses and expands a section", async () => {
    render(<Sidebar sections={sections} labels={labels} />);
    const toggle = screen.getByRole("button", { name: "Development" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    // A collapsed group stays in the DOM for the height transition but is inert.
    expect(screen.getByRole("link", { name: "Contributing" }).closest("ul")).toHaveAttribute("inert");
    await userEvent.click(toggle);
    expect(screen.getByRole("link", { name: "Contributing" }).closest("ul")).not.toHaveAttribute("inert");
  });

  it("notifies the parent when a link is used", async () => {
    let calls = 0;
    render(<Sidebar sections={sections} labels={labels} onNavigate={() => (calls += 1)} />);
    await userEvent.click(screen.getByRole("link", { name: "Overview" }));
    expect(calls).toBe(1);
  });
});
