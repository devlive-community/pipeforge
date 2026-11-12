/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { join } from "node:path";
import { render, screen } from "@testing-library/react";
import { CliTable } from "@/components/mdx/CliReference";
import type { CliReferenceData } from "@/lib/generated";
import { collectCli } from "@/scripts/cli-data.mjs";

const data: CliReferenceData = {
  name: "pipeforge",
  description: { "en-US": "The command.", "zh-CN": "命令。" },
  globalOptions: [
    {
      names: ["-o", "--output"],
      label: "<format>",
      default: "table",
      repeatable: false,
      description: { "en-US": "Output format.", "zh-CN": "输出格式。" },
    },
  ],
  commands: [
    {
      name: "validate",
      anchor: "cmd-validate",
      synopsis: "pipeforge validate [options] <file>...",
      description: { "en-US": "Check job files.", "zh-CN": "校验作业文件。" },
      arguments: [{ label: "<file>", repeatable: true, required: true, description: { "en-US": "Job files." } }],
      options: [
        {
          names: ["--var"],
          label: "<name=value>",
          default: null,
          repeatable: true,
          description: { "en-US": "A variable." },
        },
      ],
    },
  ],
  exitCodes: [{ code: 3, description: { "en-US": "Invalid.", "zh-CN": "不合法。" } }],
};

describe("CliTable", () => {
  it("renders global options, commands with anchors and exit codes", () => {
    render(<CliTable data={data} lang="en-US" />);
    expect(screen.getByRole("region", { name: "Global options" })).toHaveTextContent("-o, --output <format>");
    expect(document.getElementById("cmd-validate")).toHaveTextContent("pipeforge validate");
    expect(screen.getByText("pipeforge validate [options] <file>...")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "validate" })).toHaveTextContent("<file>... (required)");
    expect(screen.getByRole("region", { name: "validate" })).toHaveTextContent("--var <name=value> (repeatable)");
    expect(document.getElementById("exit-3")).toHaveTextContent("3Invalid.");
  });

  it("uses the page language and falls back to English", () => {
    render(<CliTable data={data} lang="zh-CN" />);
    expect(screen.getByRole("region", { name: "全局选项" })).toBeInTheDocument();
    expect(screen.getByText("校验作业文件。")).toBeInTheDocument();
    expect(screen.getByText("A variable.")).toBeInTheDocument();
    expect(screen.getByText("不合法。")).toBeInTheDocument();
  });

  it("reads the real reference of the CLI", () => {
    const cli = collectCli(join(process.cwd(), ".."));
    expect(cli.commands.map((c: { name: string }) => c.name)).toContain("run");
    expect(cli.exitCodes).toHaveLength(10);
    expect(collectCli("/nonexistent").commands).toEqual([]);
  });
});
