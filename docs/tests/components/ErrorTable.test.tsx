/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { join } from "node:path";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ErrorTable, type ErrorRow } from "@/components/mdx/ErrorTable";
import { collectErrors } from "@/scripts/errors-data.mjs";

const rows: ErrorRow[] = [
  {
    code: "PF-CONF-0001",
    name: "OPTION_REQUIRED",
    category: "CONF",
    kind: "FATAL",
    deprecated: false,
    message: "Required option 'url' is missing",
    hint: "Add it.",
  },
  {
    code: "PF-CONN-0002",
    name: "CONN_TIMEOUT",
    category: "CONN",
    kind: "TRANSIENT",
    deprecated: false,
    message: "Connecting timed out",
    hint: "Check the firewall.",
  },
  {
    code: "PF-DATA-0099",
    name: "OLD",
    category: "DATA",
    kind: "DATA",
    deprecated: true,
    message: "Old error",
    hint: "None.",
  },
];

describe("ErrorTable", () => {
  it("lists every code with an anchor, kind and deprecation marker", () => {
    render(<ErrorTable entries={rows} lang="en-US" />);
    expect(screen.getByText("3 codes")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "PF-CONF-0001" })).toHaveAttribute("href", "#pf-conf-0001");
    expect(document.getElementById("pf-conn-0002")).toHaveTextContent("Kind: TRANSIENT");
    expect(screen.getByText("Deprecated")).toBeInTheDocument();
  });

  it("filters by text and by category", async () => {
    render(<ErrorTable entries={rows} lang="zh-CN" />);
    await userEvent.type(screen.getByRole("textbox", { name: "筛选错误码" }), "firewall");
    expect(screen.getByText("1 个错误码")).toBeInTheDocument();
    expect(screen.getByText("PF-CONN-0002")).toBeInTheDocument();
    await userEvent.clear(screen.getByRole("textbox"));
    await userEvent.click(screen.getByRole("button", { name: "DATA" }));
    expect(screen.getByRole("button", { name: "DATA" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("1 个错误码")).toBeInTheDocument();
    await userEvent.type(screen.getByRole("textbox"), "timeout");
    expect(screen.getByText("没有匹配的错误码")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "全部" }));
    expect(screen.getByText("1 个错误码")).toBeInTheDocument();
  });

  it("reads the real registry in category order", () => {
    const errors = collectErrors(join(process.cwd(), ".."));
    expect(errors.length).toBeGreaterThan(10);
    expect(errors[0]).toMatchObject({ code: "PF-CONF-0001", kind: "FATAL", category: "CONF" });
    expect(errors.at(-1)?.code).toBe("PF-SYS-0001");
    expect(errors.every((e: { message: Record<string, string> }) => e.message["en-US"] && e.message["zh-CN"])).toBe(
      true,
    );
  });
});
