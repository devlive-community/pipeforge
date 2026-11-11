/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import {
  PluginCapabilities,
  PluginChangelog,
  PluginNativeOptions,
  PluginOptions,
  PluginTypeMapping,
  PluginVersions,
} from "@/components/mdx/PluginSections";

const option = (key: string, extra: Record<string, unknown> = {}) => ({
  key,
  type: "int",
  requirement: "optional",
  default: 10,
  description: `About ${key}.`,
  group: null,
  min: null,
  max: null,
  values: [],
  sensitive: false,
  deprecatedKeys: [],
  since: null,
  ...extra,
});

const plugins = [
  {
    id: "fake",
    name: "Fake",
    version: "0.1.0",
    description: "Generates rows.",
    components: [
      {
        type: "source",
        identifier: "fake",
        factory: "x",
        capabilities: ["SupportsMultiTable"],
        options: [
          option("rows", { requirement: "required", min: 0, max: 1000, since: "0.1.0", deprecatedKeys: ["row-count"] }),
          option("mode", { type: "enum", default: "BATCH", values: ["BATCH", "STREAM"] }),
          option("password", { type: "string", default: null, sensitive: true }),
        ],
        exclusive: [["rows", "rate"]],
        conditions: [{ option: "mode", value: "STREAM", required: ["rate"] }],
        nativeNamespaces: [],
      },
      { type: "sink", identifier: "fake", factory: "y", capabilities: [], options: [] },
    ],
    module: "m",
    source: "s",
    typeMapping: null,
    changelog: [],
  },
  {
    id: "jdbc",
    name: "JDBC",
    version: "1.0.0",
    description: "",
    datasource: { type: "mysql", versions: ["5.7", "8.0"] },
    components: [{ type: "source", identifier: "jdbc", factory: "z", capabilities: [], nativeNamespaces: ["jdbc"] }],
    module: "m",
    source: "s",
    typeMapping: { read: [{ external: "BIGINT", logical: "INT64", note: "unsigned becomes DECIMAL(20, 0)" }] },
    changelog: [{ commit: "abc1234", date: "2026-11-11", subject: "feat(jdbc): add jdbc" }],
  },
];

vi.mock("@/lib/generated", () => ({
  pluginEntry: (id: string) => plugins.find((p) => p.id === id),
}));

describe("plugin sections", () => {
  it("lists the capabilities of the chosen component", () => {
    render(<PluginCapabilities plugin="fake" component="source" />);
    expect(screen.getByText("source fake")).toBeInTheDocument();
    expect(screen.getByText("SupportsMultiTable")).toBeInTheDocument();
    expect(screen.queryByText("sink fake")).not.toBeInTheDocument();
  });

  it("shows the verified versions or that no system is involved", () => {
    const { unmount } = render(<PluginVersions plugin="jdbc" />);
    expect(screen.getByText("5.7, 8.0")).toBeInTheDocument();
    unmount();
    render(<PluginVersions plugin="fake" lang="zh-CN" />);
    expect(screen.getByText(/不连接外部系统/)).toBeInTheDocument();
  });

  it("renders options with their constraints and rules", () => {
    render(<PluginOptions plugin="fake" />);
    expect(document.getElementById("option-rows")).toHaveTextContent("rows");
    expect(screen.getByText("range 0 … 1000; old names: row-count; since 0.1.0")).toBeInTheDocument();
    expect(screen.getByText("values: BATCH, STREAM")).toBeInTheDocument();
    expect(screen.getByText("sensitive, masked everywhere")).toBeInTheDocument();
    expect(screen.getByText("Set exactly one of rows, rate.")).toBeInTheDocument();
    expect(screen.getByText("When mode is STREAM, also set rate.")).toBeInTheDocument();
    expect(screen.getByText("The component has no options.")).toBeInTheDocument();
    expect(screen.getAllByText("yes")).toHaveLength(1);
  });

  it("shows native namespaces, type mappings and the changelog", () => {
    render(
      <>
        <PluginNativeOptions plugin="jdbc" />
        <PluginNativeOptions plugin="fake" />
        <PluginTypeMapping plugin="jdbc" />
        <PluginTypeMapping plugin="fake" lang="zh-CN" />
        <PluginChangelog plugin="jdbc" />
        <PluginChangelog plugin="fake" />
      </>,
    );
    expect(screen.getByText("jdbc.*")).toBeInTheDocument();
    expect(screen.getByText(/passes no native configuration/)).toBeInTheDocument();
    expect(screen.getByText("unsigned becomes DECIMAL(20, 0)")).toBeInTheDocument();
    expect(screen.getByText(/没有类型映射/)).toBeInTheDocument();
    expect(screen.getByText("abc1234")).toBeInTheDocument();
    expect(screen.getByText(/No history is available/)).toBeInTheDocument();
  });

  it("says when a plugin has no data", () => {
    render(<PluginOptions plugin="nope" />);
    expect(screen.getByRole("alert")).toHaveTextContent("No documentation data for plugin 'nope'");
  });
});
