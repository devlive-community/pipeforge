/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { parse } from "yaml";
import { addEntry, flowText, readMapper, removeEntry, setEntry, setOption } from "@/features/field-mapping/yamlEdit";

const JOB = `version: 1
name: orders   # the job
source:
  - { id: src, type: fake, columns: [id INT64 NOT NULL, name STRING(20)] }
transform:
  # map the columns
  - id: map
    type: field-mapper
    mapping:
      - { from: id, to: order_id }   # keep the key
      - from: amount
        expr: "amount * 100"
        type: 'DECIMAL(14, 2)'
    drop: [password]
sink:
  - id: out
    type: console
`;

/** The lines of {@code after} that are not in {@code before}, and the other way round. */
function diff(before: string, after: string) {
  const a = before.split("\n");
  const b = after.split("\n");
  return { added: b.filter((l) => !a.includes(l)), removed: a.filter((l) => !b.includes(l)) };
}

describe("readMapper", () => {
  it("reads entries and options as written", () => {
    expect(readMapper(JOB, "map")).toEqual({
      entries: [
        { from: "id", to: "order_id" },
        { from: "amount", expr: "amount * 100", type: "DECIMAL(14, 2)" },
      ],
      drop: ["password"],
      others: "keep",
      unmapped: "fail",
      nameCase: "keep",
      order: "source",
    });
    expect(readMapper(JOB, "src")).toBeNull();
    expect(
      readMapper("version: 1\ntransform: { id: m, type: field-mapper, name-case: SNAKE, drop: a, b }\n", "m"),
    ).toMatchObject({ nameCase: "snake", drop: ["a"] });
  });
});

describe("edits", () => {
  it("replaces an entry in its own style and keeps every other line", () => {
    const flow = setEntry(JOB, "map", 0, { from: "id", to: "order_key" });
    expect(diff(JOB, flow)).toEqual({
      added: ["      - { from: id, to: order_key }   # keep the key"],
      removed: ["      - { from: id, to: order_id }   # keep the key"],
    });
    const block = setEntry(JOB, "map", 1, { from: "amount", to: "cents", type: "DECIMAL(16, 2)" });
    expect(diff(JOB, block)).toEqual({
      added: ["        to: cents", "        type: DECIMAL(16, 2)"],
      removed: ['        expr: "amount * 100"', "        type: 'DECIMAL(14, 2)'"],
    });
    expect(parse(block).transform[0].mapping[1]).toEqual({ from: "amount", to: "cents", type: "DECIMAL(16, 2)" });
  });

  it("adds entries as one line each and removes them", () => {
    const added = addEntry(JOB, "map", { value: "shop", to: "_source" });
    expect(diff(JOB, added)).toEqual({ added: ["      - { value: shop, to: _source }"], removed: [] });
    expect(added.indexOf("_source")).toBeLessThan(added.indexOf("drop:"));

    const removed = removeEntry(JOB, "map", 1);
    expect(diff(JOB, removed).removed).toEqual([
      "      - from: amount",
      '        expr: "amount * 100"',
      "        type: 'DECIMAL(14, 2)'",
    ]);
    expect(diff(JOB, removed).added).toEqual([]);
    const none = removeEntry(removed, "map", 0);
    expect(none).not.toContain("mapping:");
    expect(none).toContain("    drop: [password]");
  });

  it("creates the mapping and options of a node that has none", () => {
    const bare = "version: 1\ntransform:\n  - id: map\n    type: field-mapper   # mapper\nsink:\n  - id: out\n";
    const added = addEntry(bare, "map", { from: "a", to: "b" });
    expect(added).toBe(
      "version: 1\ntransform:\n  - id: map\n    type: field-mapper   # mapper\n    mapping:\n      - { from: a, to: b }\nsink:\n  - id: out\n",
    );
    const option = setOption(added, "map", "name-case", "snake");
    expect(parse(option).transform[0]["name-case"]).toBe("snake");
    expect(option).toContain("      - { from: a, to: b }\n    name-case: snake\nsink:");
  });

  it("rewrites a flow node in block style before changing it", () => {
    const flow = "transform:\n  - { id: map, type: field-mapper }   # short\n";
    const added = addEntry(flow, "map", { expr: "UPPER(name)", to: "loud" });
    expect(added).toBe(
      "transform:\n  - id: map\n    type: field-mapper   # short\n    mapping:\n      - { expr: UPPER(name), to: loud }\n",
    );
  });

  it("sets, replaces and removes options", () => {
    const others = setOption(JOB, "map", "others", "drop");
    expect(diff(JOB, others)).toEqual({ added: ["    others: drop"], removed: [] });
    expect(diff(others, setOption(others, "map", "others", "keep"))).toEqual({
      added: ["    others: keep"],
      removed: ["    others: drop"],
    });
    expect(setOption(others, "map", "others", undefined)).toBe(JOB);
    expect(diff(JOB, setOption(JOB, "map", "drop", ["password", "salt"]))).toEqual({
      added: ["    drop: [password, salt]"],
      removed: ["    drop: [password]"],
    });
    expect(setOption(JOB, "map", "drop", [])).not.toContain("drop:");
  });

  it("rewrites a mapping written as a flow list", () => {
    const flow = "transform:\n  - id: map\n    mapping: [{ from: a }, { from: b }]\n    drop: [x]\n";
    expect(setEntry(flow, "map", 1, { from: "b", to: "c" })).toBe(
      "transform:\n  - id: map\n    mapping:\n      - { from: a }\n      - { from: b, to: c }\n    drop: [x]\n",
    );
    expect(removeEntry(flow, "map", 0)).toContain("    mapping:\n      - { from: b }\n    drop: [x]");
  });

  it("quotes what needs quotes and leaves a broken job alone", () => {
    expect(flowText({ value: "null", type: "DECIMAL(10, 2)", nullable: false })).toBe(
      '{ value: "null", type: "DECIMAL(10, 2)", nullable: false }',
    );
    const broken = "transform:\n  - id: map\n    mapping: [\n";
    expect(addEntry(broken, "map", { from: "a" })).toBe(broken);
    expect(addEntry(JOB, "nope", { from: "a" })).toBe(JOB);
  });
});
