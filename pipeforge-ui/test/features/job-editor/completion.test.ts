/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { suggest, yamlContext } from "@/features/job-editor/completion";
import { readSchema } from "@/features/job-editor/schema";
import { readStructure } from "@/features/job-editor/structure";
import { JOB_SCHEMA } from "./schema-fixture";

const INFO = readSchema(JOB_SCHEMA, "en-US");

/** Completes at the "|" in \`text\`; returns the labels. */
function labels(text: string): string[] {
  const pos = text.indexOf("|");
  const source = text.replace("|", "");
  const context = yamlContext(source, pos);
  return context ? suggest(INFO, context, readStructure(source)).map((s) => s.label) : [];
}

describe("completion", () => {
  it("offers the job fields that are not written yet", () => {
    expect(labels("version: 1\n|")).toEqual(["name", "description", "env", "source", "transform", "sink"]);
    expect(labels("version: 1\nna|")).toContain("name");
    expect(labels("version: 1\nname: x\nsink:\n  - id: k\n|")).not.toContain("sink");
  });

  it("offers env options, their values and the node defaults", () => {
    expect(labels("version: 1\nenv:\n  parallelism: 2\n  |")).toEqual(["mode", "source", "transform", "sink"]);
    expect(labels("env:\n  mode: |")).toEqual(["batch", "streaming"]);
    expect(labels("env:\n  source:\n    |")).toEqual(["rows"]);
    expect(labels("env:\n  sink:\n    |")).toEqual(["limit"]);
  });

  it("offers node fields with the options of the node's type", () => {
    const job = "source:\n  - id: src\n    type: fake\n    |";
    expect(labels(job)).toEqual(["plugin", "parallelism", "rows", "strict"]);
    expect(labels("source:\n  - id: src\n    |\n    type: fake")).toEqual(["plugin", "parallelism", "rows", "strict"]);
    expect(labels("source:\n- id: src\n  type: fake\n  |")).toContain("rows");
    expect(labels("source:\n  - |")).toEqual(["id", "type", "plugin", "parallelism"]);
    expect(labels("source:\n  - id: a\n    type: fake\n  - id: b\n    |")).toEqual(["type", "plugin", "parallelism"]);
  });

  it("offers component types, enum and boolean values, and input ids", () => {
    expect(labels("source:\n  - id: src\n    type: |")).toEqual(["fake"]);
    expect(labels("sink:\n  - id: k\n    type: c|")).toEqual(["console"]);
    expect(labels("source:\n  - id: src\n    type: fake\n    strict: |")).toEqual(["true", "false"]);
    const job = "source:\n  - id: a\n    type: fake\ntransform:\n  - id: t\n    inputs: |\nsink:\n  - id: k";
    expect(labels(job)).toEqual(["a"]);
  });

  it("describes keys with their type, default and description", () => {
    const pos = "source:\n  - id: src\n    type: fake\n    ".length;
    const source = "source:\n  - id: src\n    type: fake\n    ";
    const rows = suggest(INFO, yamlContext(source, pos)!, []).find((s) => s.label === "rows");
    expect(rows).toEqual({
      label: "rows",
      type: "property",
      detail: "integer = 10",
      info: "Rows to produce",
      apply: "rows: ",
    });
    const zh = readSchema(JOB_SCHEMA, "zh-CN");
    expect(zh.root.find((f) => f.key === "version")?.description).toBe("DSL 版本，固定为 1");
    expect(zh.node.source.find((f) => f.key === "id")?.description).toBe("稳定 id");
  });

  it("stays quiet where it cannot help", () => {
    expect(labels("source: [{ id: a, |")).toEqual([]);
    expect(labels("name: x # comment |")).toEqual([]);
    expect(labels("env:\n  variables:\n    |")).toEqual([]);
    expect(labels("source:\n  - id: a\n    split:\n      |")).toEqual([]);
  });
});
