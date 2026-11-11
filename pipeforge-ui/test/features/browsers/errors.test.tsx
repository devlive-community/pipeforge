/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp, SERVER } from "../../render";
import { ERRORS } from "./fixtures";

describe("ErrorCodesPage", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("lists the codes with their kind, hint and parameters and filters them", async () => {
    fakeServer({ ...SERVER, "/api/v1/errors": ERRORS });
    renderApp("/errors");
    expect(await screen.findByRole("heading", { level: 2, name: "PF-CONF-0001" })).toBeInTheDocument();
    expect(screen.getByText("3 codes")).toBeInTheDocument();
    expect(screen.getByText("Set the option in the node or in env.")).toBeInTheDocument();
    expect(screen.getByText("{option}", { selector: "code" })).toBeInTheDocument();
    expect(screen.getByText("transient")).toBeInTheDocument();
    expect(screen.getByText("deprecated")).toBeInTheDocument();
    expect(screen.getByText("reason")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("radio", { name: "PLAN" }));
    expect(screen.getByText("1 codes")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: "All" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Search error codes" }), "unavailable");
    expect(screen.getByRole("heading", { level: 2, name: "PF-SYS-0001" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 2, name: "PF-CONF-0001" })).toBeNull();
    await userEvent.type(screen.getByRole("textbox", { name: "Search error codes" }), "nothing");
    expect(screen.getByText("No error code matches.")).toBeInTheDocument();
  });

  it("opens at the code in the address and asks for Chinese messages", async () => {
    usePreferences.setState({ language: "zh-CN" });
    const requests = fakeServer({ ...SERVER, "/api/v1/errors": ERRORS });
    renderApp("/errors#pf-plan-0007");
    const entry = (await screen.findByRole("heading", { level: 2, name: "PF-PLAN-0007" })).closest("article");
    await vi.waitFor(() => expect(entry).toHaveFocus());
    expect(screen.getByRole("heading", { level: 1, name: "错误码" })).toBeInTheDocument();
    expect(requests.find((r) => r.path === "/api/v1/errors")?.language).toBe("zh-CN");
  });
});
