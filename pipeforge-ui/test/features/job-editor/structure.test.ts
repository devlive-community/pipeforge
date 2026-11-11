/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { readStructure } from "@/features/job-editor/structure";

describe("readStructure", () => {
  it("reads nodes with their lines and the implied inputs", () => {
    const nodes = readStructure(
      [
        "version: 1",
        "name: demo",
        "source:",
        "  - { id: a, type: fake }",
        "  - id: b",
        "    type: fake",
        "transform:",
        "  - { id: t, type: field-mapper, inputs: [a, b] }",
        "sink:",
        "  - { id: x, type: console }",
        "  - { id: y, type: console, inputs: a }",
      ].join("\n"),
    );
    expect(nodes).toEqual([
      { id: "a", kind: "source", type: "fake", inputs: [], inputsImplied: false, line: 4 },
      { id: "b", kind: "source", type: "fake", inputs: [], inputsImplied: false, line: 5 },
      { id: "t", kind: "transform", type: "field-mapper", inputs: ["a", "b"], inputsImplied: false, line: 8 },
      { id: "x", kind: "sink", type: "console", inputs: ["t"], inputsImplied: true, line: 10 },
      { id: "y", kind: "sink", type: "console", inputs: ["a"], inputsImplied: false, line: 11 },
    ]);
  });

  it("accepts a single node without a list and survives broken documents", () => {
    expect(readStructure("source:\n  id: s\n  type: fake\nsink:\n  id: k").map((n) => n.inputs)).toEqual([[], ["s"]]);
    expect(readStructure("- just\n- a list")).toEqual([]);
    // An unfinished flow mapping still yields what can be read, so the graph keeps up while typing.
    expect(readStructure("source: [{ id: a").map((n) => n.id)).toEqual(["a"]);
    expect(readStructure("source:\n  - type: no-id\n  - id: ok")).toHaveLength(1);
  });
});
