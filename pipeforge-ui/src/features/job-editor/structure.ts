/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { isMap, isScalar, isSeq, LineCounter, parseDocument, type YAMLMap } from "yaml";

export type NodeKind = "source" | "transform" | "sink";
export const KINDS: NodeKind[] = ["source", "transform", "sink"];

/** A node as written, read on the client so the graph can be drawn even while the job is invalid. */
export interface StructureNode {
  id: string;
  kind: NodeKind;
  type: string;
  inputs: string[];
  /** Whether inputs was omitted and taken from the preceding source or transform. */
  inputsImplied: boolean;
  /** 1-based line of the node. */
  line: number;
}

function text(value: unknown): string {
  return isScalar(value) && value.value !== null && value.value !== undefined ? String(value.value) : "";
}

/**
 * Reads the nodes of a job. Mirrors the parser's rule for omitted inputs: a transform or sink
 * without inputs reads the last source or transform written before it.
 */
export function readStructure(source: string): StructureNode[] {
  const lineCounter = new LineCounter();
  const document = parseDocument(source, { lineCounter, uniqueKeys: false });
  if (!isMap(document.contents)) return [];
  const nodes: StructureNode[] = [];
  let lastProducer: string | undefined;
  for (const kind of KINDS) {
    const list = document.contents.get(kind, true);
    const items = isSeq(list) ? list.items : isMap(list) ? [list] : [];
    for (const item of items) {
      if (!isMap(item)) continue;
      const map = item as YAMLMap;
      const id = text(map.get("id", true));
      if (!id) continue;
      const written = map.get("inputs", true);
      const inputs = isSeq(written)
        ? written.items.map(text).filter(Boolean)
        : isScalar(written)
          ? [text(written)].filter(Boolean)
          : [];
      const implied = kind !== "source" && written === undefined;
      if (implied && lastProducer) inputs.push(lastProducer);
      nodes.push({
        id,
        kind,
        type: text(map.get("type", true)),
        inputs,
        inputsImplied: implied,
        line: lineCounter.linePos(map.range?.[0] ?? 0).line,
      });
      if (kind !== "sink") lastProducer = id;
    }
  }
  return nodes;
}
