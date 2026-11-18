/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { setNodeOption } from "@/features/field-mapping/yamlEdit";
import { addComponent, canConnect } from "@/features/job-editor/graphEdit";
import { readStructure } from "@/features/job-editor/structure";

const job = `version: 1
name: connected
source:
  # keep this comment
  - id: fake
    type: fake
transform:
  - id: clean
    type: field-mapper
  - id: split
    type: field-mapper
sink:
  - id: out
    type: console
`;

it("keeps the existing graph when adding an upstream component", () => {
  const added = addComponent(job, "source", "fake")!;
  expect(added.id).toBe("fake-2");
  expect(added.definition).toContain("# keep this comment");
  expect(readStructure(added.definition).map(({ id, inputs }) => [id, inputs])).toEqual([
    ["fake", []],
    ["fake-2", []],
    ["clean", ["fake"]],
    ["split", ["clean"]],
    ["out", ["split"]],
  ]);
});

it("preserves connections in a flow-style draft", () => {
  const draft = "name: inline\nsource: [{ id: first, type: fake }]\nsink: [{ id: last, type: console }]\n";
  const added = addComponent(draft, "source", "fake")!;
  expect(readStructure(added.definition).find(({ id }) => id === "last")?.inputs).toEqual(["first"]);
  const edited = setNodeOption(added.definition, "last", "inputs", ["fake"]);
  expect(readStructure(edited).find(({ id }) => id === "last")?.inputs).toEqual(["fake"]);
  expect(addComponent("source: [", "source", "fake")).toBeUndefined();
});

it("rejects edges that would create a cycle or use an incompatible endpoint", () => {
  const nodes = readStructure(job);
  expect(canConnect(nodes, "split", "clean")).toBe(false);
  expect(canConnect(nodes, "out", "clean")).toBe(false);
  expect(canConnect(nodes, "clean", "fake")).toBe(false);
  expect(canConnect(nodes, "clean", "split")).toBe(false);
  expect(canConnect(nodes, "fake", "out")).toBe(true);
});
