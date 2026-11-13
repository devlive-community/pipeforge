/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expressionCompletion } from "@/features/functions/completion";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp, SERVER } from "../../render";
import { FUNCTIONS } from "./fixtures";

async function openFunctions(routes: Record<string, unknown> = {}) {
  const requests = fakeServer({ ...SERVER, "/api/v1/functions": FUNCTIONS, ...routes });
  renderApp("/functions");
  await screen.findByRole("heading", { level: 2, name: "UPPER" });
  return requests;
}

describe("FunctionsPage", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("filters the functions by text and category", async () => {
    await openFunctions();
    expect(screen.getByText("3 functions")).toBeInTheDocument();
    expect(screen.getByText("Never NULL.")).toBeInTheDocument();
    expect(screen.getAllByText("NULL when any argument is NULL.")).toHaveLength(2);

    await userEvent.type(screen.getByRole("textbox", { name: "Search functions" }), "absolute");
    expect(screen.getByText("1 functions")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "ABS" })).toBeInTheDocument();
    await userEvent.clear(screen.getByRole("textbox", { name: "Search functions" }));

    await userEvent.click(screen.getByRole("radio", { name: "Date and time" }));
    expect(screen.getByRole("heading", { level: 2, name: "NOW" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 2, name: "UPPER" })).toBeNull();
    await userEvent.click(screen.getByRole("radio", { name: "Conversion" }));
    expect(screen.getByText("No function matches.")).toBeInTheDocument();
  });

  it("tries an example on the columns and shows the result", async () => {
    const requests = await openFunctions({
      "/api/v1/expressions/evaluate": { type: "STRING", value: "PIPE", deterministic: true },
    });
    const upper = screen.getByRole("heading", { level: 2, name: "UPPER" }).closest("article") as HTMLElement;
    await userEvent.click(within(upper).getByRole("button", { name: "Try" }));
    await waitFor(() => expect(screen.getByTestId("code-editor")).toHaveTextContent("UPPER('pipe')"));

    const amount = screen.getByRole("group", { name: "Column 2" });
    await userEvent.clear(within(amount).getByRole("textbox", { name: "Value" }));
    await userEvent.click(screen.getByRole("button", { name: "Add a column" }));
    expect(screen.getAllByRole("group")).toHaveLength(3);
    await userEvent.click(screen.getByRole("button", { name: "Remove column 3" }));
    expect(screen.getAllByRole("group")).toHaveLength(2);
    await userEvent.type(screen.getByRole("textbox", { name: "Time zone" }), "Asia/Shanghai");
    await userEvent.click(screen.getByRole("button", { name: "Evaluate" }));

    const result = screen.getByRole("region", { name: "Result" });
    expect(await within(result).findByText("PIPE")).toBeInTheDocument();
    expect(within(result).getByText("deterministic")).toBeInTheDocument();
    const sent = requests.find((r) => r.path === "/api/v1/expressions/evaluate");
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({
      expression: "UPPER('pipe')",
      columns: [
        { name: "name", type: "STRING", value: "pipeforge" },
        { name: "amount", type: "DECIMAL(10, 2)" },
      ],
      timezone: "Asia/Shanghai",
    });
  });

  it("shows NULL results and errors with their code and hint", async () => {
    await openFunctions({ "/api/v1/expressions/evaluate": { type: "STRING", deterministic: false } });
    await userEvent.click(screen.getByRole("button", { name: "Evaluate" }));
    const result = screen.getByRole("region", { name: "Result" });
    expect(await within(result).findByText("NULL")).toBeInTheDocument();
    expect(within(result).getByText("not deterministic")).toBeInTheDocument();

    fakeServer({
      ...SERVER,
      "/api/v1/expressions/evaluate": [
        400,
        {
          code: "PF-PLAN-0007",
          message: "The expression is invalid at line 1, column 7: unknown function 'UPPR'",
          hint: "Check the spelling.",
          params: { line: 1, column: 7, reason: "unknown function 'UPPR'" },
        },
      ],
    });
    await userEvent.click(screen.getByRole("button", { name: "Evaluate" }));
    expect(await within(result).findByText("PF-PLAN-0007")).toBeInTheDocument();
    expect(within(result).getByText("Check the spelling.")).toBeInTheDocument();
    await waitFor(() => expect(document.querySelector(".cm-lintRange-error")).not.toBeNull());
  });
});

describe("expressionCompletion", () => {
  const functions = [{ name: "UPPER", signatures: ["UPPER(STRING) -> STRING"], description: "Upper case." }];
  const columns = [{ name: "name", type: "STRING" }];

  it("offers columns, functions with a parenthesis and keywords for the word being typed", () => {
    const result = expressionCompletion("UP", 2, false, functions, columns);
    expect(result?.from).toBe(0);
    expect(result?.options.find((o) => o.label === "UPPER")).toMatchObject({
      apply: "UPPER(",
      detail: "UPPER(STRING) -> STRING",
      info: "Upper case.",
    });
    expect(result?.options.find((o) => o.label === "name")).toMatchObject({ detail: "STRING" });
    expect(result?.options.some((o) => o.label === "CASE")).toBe(true);
  });

  it("stays quiet inside a string and without a word unless asked", () => {
    expect(expressionCompletion("'na", 3, true, functions, columns)).toBeNull();
    expect(expressionCompletion("1 + ", 4, false, functions, columns)).toBeNull();
    expect(expressionCompletion("1 + ", 4, true, functions, columns)?.from).toBe(4);
  });
});
