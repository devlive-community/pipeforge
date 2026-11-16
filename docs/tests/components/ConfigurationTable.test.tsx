/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { join } from "node:path";
import { render, screen } from "@testing-library/react";
import { ConfigurationTable } from "@/components/mdx/ConfigurationList";
import type { SettingEntry } from "@/lib/generated";
import { collectConfiguration } from "@/scripts/configuration-data.mjs";

const entries: SettingEntry[] = [
  {
    key: "cluster.token",
    file: "conf/pipeforge.yaml",
    type: "string",
    default: null,
    derived: null,
    required: true,
    env: "PIPEFORGE_CLUSTER_TOKEN",
    description: { "en-US": "The shared token.", "zh-CN": "共享令牌。" },
  },
  {
    key: "worker.slots",
    file: "conf/worker.yaml",
    type: "int",
    default: null,
    derived: { "en-US": "the number of processors", "zh-CN": "CPU 核数" },
    required: false,
    env: "PIPEFORGE_WORKER_SLOTS",
    description: { "en-US": "Tasks at the same time." },
  },
  {
    key: "worker.data.port",
    file: "conf/worker.yaml",
    type: "int",
    default: "6124",
    derived: null,
    required: false,
    env: "PIPEFORGE_WORKER_DATA_PORT",
    description: { "en-US": "The data port." },
  },
];

describe("ConfigurationTable", () => {
  it("renders a table per file with an anchor per setting", () => {
    render(<ConfigurationTable entries={entries} lang="en-US" />);
    expect(screen.getByRole("region", { name: "conf/pipeforge.yaml" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "conf/master.yaml" })).not.toBeInTheDocument();
    expect(document.getElementById("cluster.token")).toHaveTextContent("required");
    expect(document.getElementById("worker.slots")).toHaveTextContent("the number of processors");
    expect(document.getElementById("worker.data.port")).toHaveTextContent("6124");
    expect(document.getElementById("worker.data.port")).toHaveTextContent("PIPEFORGE_WORKER_DATA_PORT");
  });

  it("uses the page language and falls back to English", () => {
    render(<ConfigurationTable entries={entries} lang="zh-CN" />);
    expect(screen.getByText("共享令牌。")).toBeInTheDocument();
    expect(screen.getByText("CPU 核数")).toBeInTheDocument();
    expect(screen.getByText("Tasks at the same time.")).toBeInTheDocument();
    expect(screen.getAllByRole("columnheader", { name: "默认值" })).toHaveLength(2);
  });

  it("reads the real reference", () => {
    const settings = collectConfiguration(join(process.cwd(), ".."));
    expect(settings.length).toBeGreaterThan(20);
    expect(settings[0]).toMatchObject({ key: "cluster.name", file: "conf/pipeforge.yaml" });
    expect(settings.every((s: SettingEntry) => s.description["en-US"] && s.description["zh-CN"])).toBe(true);
    expect(collectConfiguration("/nonexistent")).toEqual([]);
  });
});
