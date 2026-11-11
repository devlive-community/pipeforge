/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

/** A node to lay out: its id and the ids it reads from. */
export interface LayoutInput {
  id: string;
  inputs: string[];
}

export interface Placed {
  id: string;
  x: number;
  y: number;
  layer: number;
}

export const NODE_WIDTH = 176;
export const NODE_HEIGHT = 56;
const GAP_X = 72;
const GAP_Y = 28;

/**
 * Layered layout from left to right: a node's layer is one more than the deepest of its inputs,
 * so data flows to the right; nodes keep the order they are written in within a layer. Inputs
 * that do not exist and cycles are ignored, so a broken definition still gets a picture.
 */
export function layout(nodes: LayoutInput[]): { placed: Placed[]; width: number; height: number } {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const layers = new Map<string, number>();
  const visiting = new Set<string>();
  const layerOf = (id: string): number => {
    const known = layers.get(id);
    if (known !== undefined) return known;
    if (visiting.has(id)) return 0;
    visiting.add(id);
    const inputs = (byId.get(id)?.inputs ?? []).filter((i) => byId.has(i) && i !== id);
    const layer = inputs.length === 0 ? 0 : 1 + Math.max(...inputs.map(layerOf));
    visiting.delete(id);
    layers.set(id, layer);
    return layer;
  };
  const rows = new Map<number, number>();
  const placed = nodes.map((node) => {
    const layer = layerOf(node.id);
    const row = rows.get(layer) ?? 0;
    rows.set(layer, row + 1);
    return { id: node.id, layer, x: layer * (NODE_WIDTH + GAP_X), y: row * (NODE_HEIGHT + GAP_Y) };
  });
  const width = placed.reduce((w, p) => Math.max(w, p.x + NODE_WIDTH), 0);
  const height = placed.reduce((h, p) => Math.max(h, p.y + NODE_HEIGHT), 0);
  return { placed, width, height };
}

/** An edge from the right side of one node to the left side of another, as a smooth curve. */
export function edgePath(from: Placed, to: Placed): string {
  const x1 = from.x + NODE_WIDTH;
  const y1 = from.y + NODE_HEIGHT / 2;
  const x2 = to.x;
  const y2 = to.y + NODE_HEIGHT / 2;
  const bend = Math.max(32, (x2 - x1) / 2);
  return `M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`;
}
