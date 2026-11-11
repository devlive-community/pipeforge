/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp, SERVER } from "../../render";
import { FAKE_SOURCE, PLUGINS } from "./fixtures";

describe("PluginsPage", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("lists the plugins and shows the options of the chosen component", async () => {
    const requests = fakeServer({
      ...SERVER,
      "/api/v1/plugins": PLUGINS,
      "/api/v1/components/source/fake": FAKE_SOURCE,
    });
    const { router } = renderApp("/plugins");
    expect(await screen.findByText("Generates rows for tests and demos.")).toBeInTheDocument();
    expect(screen.getByText("fake@0.1.0")).toBeInTheDocument();
    expect(screen.getByText("Choose a component to see its options.")).toBeInTheDocument();
    expect(screen.getByText("plugins/broken-1.0.0")).toBeInTheDocument();

    const source = screen.getByRole("button", { name: "Source fake" });
    await userEvent.click(source);
    expect(source).toHaveAttribute("aria-pressed", "true");
    expect(router.state.location.search).toBe("?type=source&component=fake&plugin=fake%400.1.0");
    const details = (await screen.findByRole("heading", { name: "Options of Source fake" })).closest("section");
    expect(await within(details as HTMLElement).findByText("Rows to produce")).toBeInTheDocument();
    expect(within(details as HTMLElement).getByText("required")).toBeInTheDocument();
    expect(within(details as HTMLElement).getByText("Set exactly one of: rows, rate")).toBeInTheDocument();
    expect(within(details as HTMLElement).getByText("When mode is random, also set: seed")).toBeInTheDocument();
    const lookup = requests.find((r) => r.path === "/api/v1/components/source/fake");
    expect(lookup?.search).toBe("?plugin=fake%400.1.0");
  });

  it("opens at the component named in the address and reports a failed lookup", async () => {
    fakeServer({
      ...SERVER,
      "/api/v1/plugins": PLUGINS,
      "/api/v1/components/sink/blackhole": [404, { code: "PF-PLUG-0001", message: "No sink 'blackhole'" }],
    });
    renderApp("/plugins?type=sink&component=blackhole&plugin=fake%400.1.0");
    expect(await screen.findByText("No sink 'blackhole'")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sink blackhole" })).toHaveAttribute("aria-pressed", "true");
  });

  it("explains how to install plugins when there are none", async () => {
    fakeServer({ ...SERVER, "/api/v1/plugins": { plugins: [], unavailable: [] } });
    renderApp("/plugins");
    expect(await screen.findByText("No plugins are installed.")).toBeInTheDocument();
  });
});
