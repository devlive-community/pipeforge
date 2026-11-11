/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { Search } from "@/components/layout/Search";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const documents = [
  {
    id: 0,
    title: "Overview",
    url: "/en-US/next/getting-started/overview/",
    section: "getting-started",
    headings: "Architecture Roadmap",
    text: "PipeForge is a pluggable data integration platform.",
  },
  {
    id: 1,
    title: "Local development environment",
    url: "/en-US/next/development/local-environment/",
    section: "development",
    headings: "Quick start",
    text: "Start MySQL with binlog and PostgreSQL.",
  },
  {
    id: 2,
    title: "本地开发环境",
    url: "/zh-CN/next/development/local-environment/",
    section: "development",
    headings: "快速上手",
    text: "一条命令拉起数据库。",
  },
];

const labels = {
  search: "Search",
  searchPlaceholder: "Search the documentation",
  searchEmpty: "No results",
  searchHint: "hint",
};

beforeEach(() => {
  push.mockReset();
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ json: () => Promise.resolve(documents) }));
});

async function openSearch() {
  render(<Search lang="en-US" labels={labels} />);
  await userEvent.click(screen.getByRole("button", { name: /Search/ }));
  const input = await screen.findByRole("combobox", { name: "Search the documentation" });
  await waitFor(() => expect(fetch).toHaveBeenCalledWith("/search/en-US.json"));
  return input;
}

describe("Search", () => {
  it("opens with the keyboard shortcut and focuses the input", async () => {
    render(<Search lang="en-US" labels={labels} />);
    await userEvent.keyboard("{Control>}k{/Control}");
    expect(await screen.findByRole("dialog", { name: "Search" })).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toHaveFocus();
  });

  it("lists matching pages and opens the highlighted one with Enter", async () => {
    const input = await openSearch();
    await userEvent.type(input, "binlog");
    const options = await screen.findAllByRole("option");
    expect(options[0]).toHaveTextContent("Local development environment");
    expect(options[0]).toHaveAttribute("aria-selected", "true");
    await userEvent.keyboard("{Enter}");
    expect(push).toHaveBeenCalledWith("/en-US/next/development/local-environment/");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("moves the highlight with the arrow keys", async () => {
    const input = await openSearch();
    await userEvent.type(input, "p");
    const options = await screen.findAllByRole("option");
    expect(options.length).toBeGreaterThan(1);
    await userEvent.keyboard("{ArrowDown}");
    expect(screen.getAllByRole("option")[1]).toHaveAttribute("aria-selected", "true");
    await userEvent.keyboard("{ArrowUp}");
    expect(screen.getAllByRole("option")[0]).toHaveAttribute("aria-selected", "true");
  });

  it("says when nothing matches", async () => {
    const input = await openSearch();
    await userEvent.type(input, "zzzz");
    expect(await screen.findByText("No results")).toBeInTheDocument();
  });

  it("closes with Escape and returns focus to the trigger", async () => {
    await openSearch();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.getByRole("button", { name: /Search/ })).toHaveFocus();
  });
});
