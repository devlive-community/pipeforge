/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp, SERVER } from "../render";

describe("OverviewPage", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("shows the server, the counts and the plugins that could not be loaded", async () => {
    const requests = fakeServer(SERVER);
    renderApp("/");
    expect(await screen.findByText("Connected")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Overview" })).toBeInTheDocument();
    expect(screen.getByText("21.0.10")).toBeInTheDocument();
    expect(await screen.findByText("1 could not be loaded")).toBeInTheDocument();
    expect(await screen.findByText("3")).toBeInTheDocument();
    expect(screen.getByText("Registered")).toBeInTheDocument();
    const unavailable = screen.getByRole("heading", { name: "Plugins that could not be loaded" }).closest("section");
    expect(within(unavailable as HTMLElement).getByText("plugins/broken-1.0.0")).toBeInTheDocument();
    expect(requests.map((r) => r.language)).toEqual(["en-US", "en-US", "en-US", "en-US"]);
  });

  it("explains when the server cannot be reached and retries", async () => {
    fakeServer({ "/api/v1/version": [503, { code: "PF-SYS-0001", message: "down" }] });
    renderApp("/");
    expect(await screen.findByRole("heading", { name: "The server cannot be reached" })).toBeInTheDocument();
    const requests = fakeServer(SERVER);
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("Connected")).toBeInTheDocument();
    expect(requests.some((r) => r.path === "/api/v1/version")).toBe(true);
  });

  it("speaks Chinese and asks the server for Chinese messages", async () => {
    usePreferences.setState({ language: "zh-CN" });
    const requests = fakeServer({ ...SERVER, "/api/v1/plugins": { plugins: [], unavailable: [] } });
    renderApp("/");
    expect(await screen.findByText("已连接")).toBeInTheDocument();
    expect(await screen.findByText("全部已加载")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "概览" })).toBeInTheDocument();
    expect(requests.every((r) => r.language === "zh-CN")).toBe(true);
  });
});
