/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Geometry of the home page DAG: Source → Transform → Sink on a 640 × 260 stage.

export interface DagNode {
  id: string;
  label: string;
  kind: "source" | "transform" | "sink";
  x: number;
  y: number;
}

export interface DagEdge {
  from: string;
  to: string;
  path: string;
}

export const STAGE = { width: 640, height: 260 };
export const NODE = { width: 132, height: 44 };

export const NODES: DagNode[] = [
  { id: "mysql", label: "MySQL CDC", kind: "source", x: 20, y: 48 },
  { id: "kafka", label: "Kafka", kind: "source", x: 20, y: 168 },
  { id: "mapper", label: "field-mapper", kind: "transform", x: 254, y: 108 },
  { id: "doris", label: "Doris", kind: "sink", x: 488, y: 48 },
  { id: "iceberg", label: "Iceberg", kind: "sink", x: 488, y: 168 },
];

function centerRight(node: DagNode) {
  return { x: node.x + NODE.width, y: node.y + NODE.height / 2 };
}

function centerLeft(node: DagNode) {
  return { x: node.x, y: node.y + NODE.height / 2 };
}

function curve(from: DagNode, to: DagNode) {
  const a = centerRight(from);
  const b = centerLeft(to);
  const mid = (a.x + b.x) / 2;
  return `M ${a.x} ${a.y} C ${mid} ${a.y}, ${mid} ${b.y}, ${b.x} ${b.y}`;
}

const byId = new Map(NODES.map((n) => [n.id, n]));
const link = (from: string, to: string): DagEdge => ({ from, to, path: curve(byId.get(from)!, byId.get(to)!) });

export const EDGES: DagEdge[] = [
  link("mysql", "mapper"),
  link("kafka", "mapper"),
  link("mapper", "doris"),
  link("mapper", "iceberg"),
];

/** Every node upstream or downstream of `id`, including itself. */
export function lineage(id: string): Set<string> {
  const result = new Set([id]);
  const walk = (current: string, direction: "up" | "down") => {
    for (const edge of EDGES) {
      const [here, there] = direction === "down" ? [edge.from, edge.to] : [edge.to, edge.from];
      if (here === current && !result.has(there)) {
        result.add(there);
        walk(there, direction);
      }
    }
  };
  walk(id, "up");
  walk(id, "down");
  return result;
}
