/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp, SERVER } from "./render";

describe("App shell", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("navigates and shows a page for unknown addresses", async () => {
    fakeServer(SERVER);
    const { router } = renderApp("/somewhere");
    expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("link", { name: "Back to the overview" }));
    expect(router.state.location.pathname).toBe("/");
    const nav = screen.getByRole("navigation", { name: "Main navigation" });
    expect(screen.getByRole("link", { name: "Overview" })).toHaveAttribute("aria-current", "page");
    expect(nav).toBeInTheDocument();
  });

  it("switches language and theme from the top bar", async () => {
    fakeServer(SERVER);
    renderApp("/");
    await userEvent.click(screen.getByRole("button", { name: "Language" }));
    await userEvent.click(screen.getByRole("menuitemradio", { name: "中文" }));
    expect(await screen.findByRole("heading", { level: 1, name: "概览" })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("zh-CN");

    await userEvent.click(screen.getByRole("button", { name: "主题" }));
    await userEvent.click(screen.getByRole("menuitemradio", { name: "深色" }));
    expect(document.documentElement).toHaveClass("dark");
    expect(screen.getByRole("button", { name: "主题" })).toHaveFocus();
  });

  it("follows the system theme while in system mode", async () => {
    const { systemTheme } = await import("./setup");
    fakeServer(SERVER);
    renderApp("/");
    await screen.findByText("Connected");
    systemTheme.dark = true;
    systemTheme.listeners.forEach((listener) => listener());
    expect(document.documentElement).toHaveClass("dark");
  });

  it("offers a skip link to the page content", async () => {
    fakeServer(SERVER);
    renderApp("/");
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#content");
    expect(document.getElementById("content")).toBeInTheDocument();
  });
});
