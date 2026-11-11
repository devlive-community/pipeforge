/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Field, SchemaInfo } from "./schema";
import type { NodeKind, StructureNode } from "./structure";

/** Where the cursor is, as far as completion is concerned. */
export interface YamlContext {
  /** A key is being written, or the value of `key`. */
  position: "key" | "value";
  /** The mapping the cursor is in: [] for the job, ["env"], ["env", "source"], or a node. */
  scope:
    | { area: "root" }
    | { area: "env" }
    | { area: "envDefaults"; kind: NodeKind }
    | { area: "node"; kind: NodeKind; type: string; id: string };
  key?: string;
  /** What has been typed of the key or value so far, and where it starts. */
  typed: string;
  from: number;
  /** Keys already written in the same mapping. */
  siblings: string[];
}

const KINDS = new Set(["source", "transform", "sink"]);

interface Line {
  /** Column where the key starts (after "- " for list items). */
  indent: number;
  /** Column of the dash of a list item, or null. */
  dash: number | null;
  key: string | null;
  value: string;
}

function parseLine(text: string | undefined): Line | null {
  if (text === undefined || text.trim() === "" || text.trim().startsWith("#")) return null;
  const leading = text.length - text.trimStart().length;
  const dash = /^(\s*)-(\s+|$)/.exec(text);
  const rest = dash ? text.slice(dash[0].length) : text.slice(leading);
  const key = /^([A-Za-z0-9_.-]+)\s*:(\s|$)/.exec(rest);
  return {
    indent: dash ? dash[0].length : leading,
    dash: dash ? leading : null,
    key: key ? (key[1] as string) : null,
    value: key ? rest.slice(key[0].length).trim() : rest.trim(),
  };
}

/** Keys at `indent` from line `start` on, until the mapping ends (a smaller indent or a new list item). */
function keysOf(lines: string[], start: number, indent: number, item: boolean): Map<string, string> {
  const keys = new Map<string, string>();
  for (let i = start; i < lines.length; i++) {
    const line = parseLine(lines[i]);
    if (!line) continue;
    if (i > start && item && line.dash !== null && line.indent <= indent) break;
    if (i > start && line.dash === null && line.indent < indent) break;
    if (line.indent === indent && line.key) keys.set(line.key, line.value);
  }
  return keys;
}

/** Finds the context at `pos` in a block-style YAML job; flow style ({ a: 1 }) is not completed. */
export function yamlContext(source: string, pos: number): YamlContext | null {
  const lineStart = source.lastIndexOf("\n", pos - 1) + 1;
  const before = source.slice(lineStart, pos);
  const keyTyping = /^(\s*)(-\s+)?([A-Za-z0-9_.-]*)$/.exec(before);
  const valueTyping = keyTyping ? null : /^(\s*)(-\s+)?([A-Za-z0-9_.-]+):\s+([^\s#{}[\],]*)$/.exec(before);
  const match = keyTyping ?? valueTyping;
  if (!match) return null;
  const indent = (match[1] as string).length + (match[2]?.length ?? 0);
  const typed = keyTyping ? (match[3] as string) : (match[4] as string);
  const lines = source.split("\n");
  const current = source.slice(0, lineStart).split("\n").length - 1;

  // Walk up to the parent keys; remember whether each level is a list item.
  const chain: { key: string; item: number | null; line: number }[] = [];
  let need = indent;
  let item: number | null = match[2] !== undefined ? current : null;
  for (let i = current - 1; i >= 0 && need > 0; i--) {
    const line = parseLine(lines[i]);
    if (!line) continue;
    if (item === null && line.dash !== null && line.indent === need) {
      item = i;
      continue;
    }
    const keyIndent = line.dash ?? line.indent;
    if (line.key && line.value === "" && keyIndent < need) {
      chain.unshift({ key: line.key, item, line: i });
      need = keyIndent;
      item = null;
    } else if (line.dash === null && line.indent < need) {
      need = line.indent;
    }
  }

  const path = chain.map((c) => c.key);
  const innermost = chain[chain.length - 1];
  let scope: YamlContext["scope"] | null = null;
  let siblings: Map<string, string>;
  if (path.length === 0) {
    scope = { area: "root" };
    siblings = keysOf(lines, 0, 0, false);
  } else if (path.length === 1 && KINDS.has(path[0] as string) && innermost?.item !== null && innermost) {
    siblings = keysOf(lines, innermost.item as number, indent, true);
    scope = { area: "node", kind: path[0] as NodeKind, type: siblings.get("type") ?? "", id: siblings.get("id") ?? "" };
  } else {
    siblings = keysOf(lines, (innermost?.line ?? -1) + 1, indent, false);
    if (path.length === 1 && path[0] === "env") scope = { area: "env" };
    else if (path.length === 2 && path[0] === "env" && KINDS.has(path[1] as string))
      scope = { area: "envDefaults", kind: path[1] as NodeKind };
  }
  if (!scope) return null;
  const written = [...siblings.keys()].filter(
    (k) => !(keyTyping && k === typed && lines[current]?.trim().endsWith(typed)),
  );
  return {
    position: keyTyping ? "key" : "value",
    scope,
    key: valueTyping ? (match[3] as string) : undefined,
    typed,
    from: pos - typed.length,
    siblings: written,
  };
}

export interface Suggestion {
  label: string;
  type: "property" | "enum" | "variable";
  detail?: string;
  info?: string;
  apply: string;
}

function fieldsFor(info: SchemaInfo, context: YamlContext): Field[] {
  const scope = context.scope;
  switch (scope.area) {
    case "root":
      return info.root;
    case "env":
      return info.env;
    case "envDefaults":
      return info.envDefaults[scope.kind];
    case "node":
      return [...info.node[scope.kind], ...(info.byType[scope.kind][scope.type] ?? [])];
  }
}

/** The suggestions for a context; keys already written in the mapping are left out. */
export function suggest(info: SchemaInfo, context: YamlContext, nodes: StructureNode[]): Suggestion[] {
  const fields = fieldsFor(info, context);
  if (context.position === "key") {
    return fields
      .filter((f) => !context.siblings.includes(f.key) && !f.deprecated)
      .map((f) => ({
        label: f.key,
        type: "property",
        detail: f.defaultValue !== undefined ? `${f.detail} = ${String(f.defaultValue)}` : f.detail,
        info: f.description || undefined,
        apply: `${f.key}: `,
      }));
  }
  const scope = context.scope;
  if (scope.area === "node" && context.key === "type") {
    return info.types[scope.kind].map((t) => ({ label: t, type: "enum", apply: t }));
  }
  if (scope.area === "node" && context.key === "inputs") {
    return nodes
      .filter((n) => n.kind !== "sink" && n.id !== scope.id)
      .map((n) => ({ label: n.id, type: "variable", detail: n.kind, apply: n.id }));
  }
  const field = fields.find((f) => f.key === context.key);
  return (field?.values ?? []).map((v) => ({ label: v, type: "enum", apply: v }));
}
