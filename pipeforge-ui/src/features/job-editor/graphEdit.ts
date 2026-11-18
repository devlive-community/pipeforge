/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { isMap, isSeq, parseDocument } from "yaml";
import { appendNode, setNodeOption } from "@/features/field-mapping/yamlEdit";
import { readStructure, type NodeKind, type StructureNode } from "./structure";

export const COMPONENT_DRAG = "application/x-pipeforge-component";

export function addComponent(
  definition: string,
  kind: NodeKind,
  type: string,
): { definition: string; id: string } | undefined {
  const source = definition.trim() ? definition : "version: 1\nname: job\n";
  const document = parseDocument(source);
  if (document.errors.length || !isMap(document.contents)) return;
  const section = document.contents.get(kind, true);
  if (section != null && !isSeq(section) && !isMap(section)) return;

  const nodes = readStructure(source);
  const ids = new Set(nodes.map((node) => node.id));
  let id = type;
  for (let suffix = 2; ids.has(id); suffix += 1) id = `${type.slice(0, 58)}-${suffix}`;

  // Adding an upstream node must not change the inputs of existing nodes that used the default.
  let edited = source;
  if (kind !== "sink") {
    const firstTransform = nodes.find((node) => node.kind === "transform");
    const downstream =
      kind === "source" && firstTransform ? [firstTransform] : nodes.filter((node) => node.kind === "sink");
    for (const node of downstream) {
      if (!node.inputsImplied || node.inputs.length === 0) continue;
      const next = setNodeOption(edited, node.id, "inputs", node.inputs);
      if (next === edited) return;
      edited = next;
    }
  }
  const updated = appendNode(edited, kind, { id, type });
  if (updated !== edited && parseDocument(updated).errors.length === 0) return { definition: updated, id };
}

export function canConnect(nodes: StructureNode[], from: string, to: string) {
  const byId = new Map(nodes.map((node) => [node.id, node]));
  const producer = byId.get(from);
  const consumer = byId.get(to);
  if (!producer || !consumer || producer.kind === "sink" || consumer.kind === "source") return false;
  if (consumer.inputs.includes(from)) return false;
  const pending = [producer.id];
  const visited = new Set<string>();
  while (pending.length > 0) {
    const id = pending.pop()!;
    if (id === to) return false;
    if (visited.has(id)) continue;
    visited.add(id);
    pending.push(...(byId.get(id)?.inputs ?? []));
  }
  return true;
}
