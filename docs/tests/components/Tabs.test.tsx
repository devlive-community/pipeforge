/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Tab, Tabs } from "@/components/mdx/Tabs";

function renderTabs() {
  render(
    <Tabs>
      <Tab label="YAML">yaml body</Tab>
      <Tab label="SQL">sql body</Tab>
      <Tab label="CLI">cli body</Tab>
    </Tabs>,
  );
}

describe("Tabs", () => {
  it("shows the first panel and marks its tab as selected", () => {
    renderTabs();
    expect(screen.getByRole("tab", { name: "YAML" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tabpanel")).toHaveTextContent("yaml body");
    expect(screen.getByText("sql body")).not.toBeVisible();
  });

  it("switches panels on click", async () => {
    renderTabs();
    await userEvent.click(screen.getByRole("tab", { name: "SQL" }));
    expect(screen.getByRole("tab", { name: "SQL" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tabpanel")).toHaveTextContent("sql body");
  });

  it("moves between tabs with arrow keys, Home and End", async () => {
    renderTabs();
    screen.getByRole("tab", { name: "YAML" }).focus();
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "SQL" })).toHaveFocus();
    expect(screen.getByRole("tabpanel")).toHaveTextContent("sql body");
    await userEvent.keyboard("{End}");
    expect(screen.getByRole("tab", { name: "CLI" })).toHaveAttribute("aria-selected", "true");
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "YAML" })).toHaveFocus();
    await userEvent.keyboard("{ArrowLeft}{Home}");
    expect(screen.getByRole("tab", { name: "YAML" })).toHaveAttribute("aria-selected", "true");
  });

  it("keeps only the selected tab in the tab order and links tabs to panels", () => {
    renderTabs();
    const tabs = screen.getAllByRole("tab");
    expect(tabs.map((t) => t.getAttribute("tabindex"))).toEqual(["0", "-1", "-1"]);
    const panel = screen.getByRole("tabpanel");
    expect(panel).toHaveAttribute("aria-labelledby", tabs[0]!.id);
    expect(tabs[0]).toHaveAttribute("aria-controls", panel.id);
  });
});
