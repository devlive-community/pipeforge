/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { diagnosticsOf, problemsOf } from "@/features/job-editor/problems";
import type { StructureNode } from "@/features/job-editor/structure";
import { toRanges } from "@/ui/CodeEditor";

const NODES: StructureNode[] = [
  { id: "src", kind: "source", type: "fake", inputs: [], inputsImplied: false, line: 9 },
  { id: "out", kind: "sink", type: "console", inputs: ["src"], inputsImplied: true, line: 15 },
];

describe("problems", () => {
  it("places errors at their definition location or at their node", () => {
    const problems = problemsOf(
      {
        valid: false,
        errors: [
          { code: "PF-CONF-0006", message: "bad", hint: "fix it", scope: { line: 3, column: 7, path: "name" } },
          { code: "PF-PLUG-0001", message: "missing", scope: { node: "src" } },
          { code: "PF-SYS-0001", message: "anywhere", scope: {} },
        ],
        warnings: ["node 'out' produces no tables", "env.sink.x is not an option of any sink in the job"],
      },
      NODES,
    );
    expect(problems).toEqual([
      { severity: "error", message: "bad", code: "PF-CONF-0006", hint: "fix it", line: 3, column: 7 },
      { severity: "error", message: "missing", code: "PF-PLUG-0001", hint: undefined, line: 9 },
      { severity: "error", message: "anywhere", code: "PF-SYS-0001", hint: undefined },
      { severity: "warning", message: "node 'out' produces no tables", line: 15 },
      { severity: "warning", message: "env.sink.x is not an option of any sink in the job", line: undefined },
    ]);
    expect(diagnosticsOf(problems)).toEqual([
      { line: 3, column: 7, severity: "error", message: "PF-CONF-0006 bad" },
      { line: 9, column: undefined, severity: "error", message: "PF-PLUG-0001 missing" },
      { line: 15, column: undefined, severity: "warning", message: "node 'out' produces no tables" },
    ]);
    expect(problemsOf(undefined, NODES)).toEqual([]);
  });

  it("turns lines and columns into editor ranges", () => {
    const text = "ab\ncdef\ng";
    expect(toRanges(text, [{ line: 2, column: 2, severity: "error", message: "m" }])).toEqual([
      { from: 4, to: 7, severity: "error", message: "m" },
    ]);
    expect(toRanges(text, [{ line: 9, severity: "warning", message: "past the end" }])[0]).toMatchObject({
      from: 8,
      to: 9,
    });
    expect(toRanges(text, [{ line: 1, column: 50, severity: "info", message: "long" }])[0]).toMatchObject({
      from: 2,
      to: 2,
    });
  });
});
