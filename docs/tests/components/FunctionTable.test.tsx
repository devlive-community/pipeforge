/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { join } from "node:path";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FunctionTable, type FunctionRow } from "@/components/mdx/FunctionTable";
import { collectFunctions } from "@/scripts/functions-data.mjs";

const rows: FunctionRow[] = [
  {
    name: "ABS",
    category: "numeric",
    syntax: null,
    deterministic: true,
    signatures: ["ABS(INT32) → INT32"],
    description: "Absolute value.",
    nulls: null,
    examples: [{ expression: "ABS(-3)", result: "3" }],
  },
  {
    name: "SUBSTRING",
    category: "string",
    syntax: "SUBSTRING(value FROM start [FOR length])",
    deterministic: true,
    signatures: ["SUBSTRING(STRING, INT64) → STRING"],
    description: "Part of a string.",
    nulls: null,
    examples: [{ expression: "SUBSTRING('pipeforge', 5)", result: "'forge'" }],
  },
  {
    name: "NOW",
    category: "datetime",
    syntax: null,
    deterministic: false,
    signatures: ["NOW() → TIMESTAMP_LTZ(3)"],
    description: "The current time.",
    nulls: "Never NULL.",
    examples: [],
  },
];

describe("FunctionTable", () => {
  it("lists every function with an anchor, signatures, NULL behavior and examples", () => {
    render(<FunctionTable entries={rows} lang="en-US" />);
    expect(screen.getByText("3 functions")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "SUBSTRING" })).toHaveAttribute("href", "#substring");
    const abs = document.getElementById("abs");
    expect(abs).toHaveTextContent("ABS(INT32) → INT32");
    expect(abs).toHaveTextContent("NULL when any argument is NULL.");
    expect(abs).toHaveTextContent("→ 3");
    expect(document.getElementById("now")).toHaveTextContent("Never NULL.");
    expect(screen.getAllByText("Not deterministic")).toHaveLength(1);
  });

  it("filters by text and by category", async () => {
    render(<FunctionTable entries={rows} lang="zh-CN" />);
    await userEvent.type(screen.getByRole("textbox", { name: "筛选函数" }), "string");
    expect(screen.getByText("1 个函数")).toBeInTheDocument();
    await userEvent.clear(screen.getByRole("textbox"));
    await userEvent.click(screen.getByRole("button", { name: "日期时间" }));
    expect(screen.getByRole("button", { name: "日期时间" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("1 个函数")).toBeInTheDocument();
    await userEvent.type(screen.getByRole("textbox"), "abs");
    expect(screen.getByText("没有匹配的函数")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "全部" }));
    expect(screen.getByText("1 个函数")).toBeInTheDocument();
  });

  it("reads the real catalog in category order with both languages", () => {
    const functions = collectFunctions(join(process.cwd(), ".."));
    expect(functions.length).toBeGreaterThan(80);
    expect(functions[0].category).toBe("comparison");
    expect(functions.at(-1)?.category).toBe("conversion");
    expect(functions.find((f: { name: string }) => f.name === "RAND")).toMatchObject({ deterministic: false });
    expect(
      functions.every(
        (f: { description: Record<string, string>; examples: unknown[] }) =>
          f.description["en-US"] && f.description["zh-CN"] && f.examples.length > 0,
      ),
    ).toBe(true);
  });
});
