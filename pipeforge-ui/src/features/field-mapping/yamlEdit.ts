/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { Document, isMap, isScalar, isSeq, parseDocument, type Node as YamlNode, type Pair, type YAMLMap } from "yaml";

/** One entry of a field mapper's {@code mapping}; every field is optional, as in YAML. */
export interface MappingEntry {
  from?: string;
  to?: string;
  expr?: string;
  value?: string;
  type?: string;
  nullable?: boolean;
  default?: string;
  comment?: string;
}

/** The order the keys of an entry are written in. */
export const ENTRY_KEYS = ["from", "expr", "value", "to", "type", "nullable", "default", "comment"] as const;

/** The options of a field mapper node as written in the job. */
export interface MapperOptions {
  entries: MappingEntry[];
  drop: string[];
  others: "keep" | "drop";
  unmapped: "fail" | "drop";
  nameCase: "keep" | "lower" | "upper" | "snake" | "camel";
  order: "source" | "mapping";
}

/** Node option keys the editor changes. */
export type OptionKey = "drop" | "others" | "unmapped" | "name-case" | "order";

const PARSE = { uniqueKeys: false } as const;
const FLOW_OUT = { lineWidth: 0, flowCollectionPadding: true } as const;

function scalar(node: unknown): string | undefined {
  if (!isScalar(node) || node.value === null || node.value === undefined) return undefined;
  return String(node.value);
}

/** The node map of {@code nodeId} in the transform list, if it is there. */
function findNode(document: Document.Parsed, nodeId: string): YAMLMap | undefined {
  if (!isMap(document.contents)) return undefined;
  const list = document.contents.get("transform", true);
  const items = isSeq(list) ? list.items : isMap(list) ? [list] : [];
  return items.find((item): item is YAMLMap => isMap(item) && scalar(item.get("id", true)) === nodeId);
}

function pairOf(map: YAMLMap, key: string): Pair<YamlNode, YamlNode> | undefined {
  return map.items.find((pair) => scalar(pair.key) === key) as Pair<YamlNode, YamlNode> | undefined;
}

function entryOf(node: unknown): MappingEntry {
  const entry: MappingEntry = {};
  if (!isMap(node)) return entry;
  for (const key of ENTRY_KEYS) {
    const value = node.get(key, true);
    if (value === undefined) continue;
    if (key === "nullable") {
      const text = scalar(value);
      if (text === "true" || text === "false") entry.nullable = text === "true";
    } else {
      const text = scalar(value);
      if (text !== undefined) entry[key] = text;
    }
  }
  return entry;
}

function choice<T extends string>(map: YAMLMap, key: string, allowed: readonly T[], fallback: T): T {
  const text = scalar(map.get(key, true))?.toLowerCase().replace(/_/g, "-");
  return allowed.find((a) => a === text) ?? fallback;
}

/** Reads the options of field mapper {@code nodeId}; null when the job has no such transform. */
export function readMapper(source: string, nodeId: string): MapperOptions | null {
  const node = findNode(parseDocument(source, PARSE), nodeId);
  if (!node) return null;
  const mapping = node.get("mapping", true);
  const drop = node.get("drop", true);
  return {
    entries: isSeq(mapping) ? mapping.items.map(entryOf) : [],
    drop: isSeq(drop)
      ? drop.items.map(scalar).filter((d): d is string => !!d)
      : scalar(drop)
        ? String(scalar(drop))
            .split(",")
            .map((d) => d.trim())
            .filter(Boolean)
        : [],
    others: choice(node, "others", ["keep", "drop"] as const, "keep"),
    unmapped: choice(node, "unmapped", ["fail", "drop"] as const, "fail"),
    nameCase: choice(node, "name-case", ["keep", "lower", "upper", "snake", "camel"] as const, "keep"),
    order: choice(node, "order", ["source", "mapping"] as const, "source"),
  };
}

// ------------------------------------------------------------------ writing

function plain(entry: MappingEntry): Record<string, string | boolean> {
  const out: Record<string, string | boolean> = {};
  for (const key of ENTRY_KEYS) {
    const value = entry[key];
    if (value !== undefined && value !== "") out[key] = value;
  }
  if (entry.value === "") out.value = "";
  return out;
}

/** {@code value} in flow style on one line, e.g. {@code { from: id, to: order_id }}. */
export function flowText(value: unknown): string {
  const document = new Document(value);
  if (isMap(document.contents) || isSeq(document.contents)) document.contents.flow = true;
  return document.toString(FLOW_OUT).trimEnd();
}

/** A list of plain values on one line: {@code [a, b]}. */
function listText(values: string[]): string {
  return "[" + values.map((v) => flowText(v)).join(", ") + "]";
}

/** {@code value} in block style, its lines after the first indented to {@code column}. */
function blockText(value: unknown, column: number, flowEntries = false): string {
  const document = new Document(value);
  if (flowEntries && isMap(document.contents)) {
    const mapping = document.contents.get("mapping", true);
    if (isSeq(mapping)) {
      for (const item of mapping.items) if (isMap(item)) item.flow = true;
    }
  }
  const lines = document.toString(FLOW_OUT).trimEnd().split("\n");
  return lines.map((line, i) => (i === 0 ? line : " ".repeat(column) + line)).join("\n");
}

function lineStart(source: string, index: number): number {
  return source.lastIndexOf("\n", index - 1) + 1;
}

/** The index just after the end of the line that holds {@code index - 1}. */
function lineEnd(source: string, index: number): number {
  const newline = source.indexOf("\n", Math.max(0, index - 1));
  return newline < 0 ? source.length : newline + 1;
}

function column(source: string, index: number): number {
  return index - lineStart(source, index);
}

/** Where the content of a node ends, without trailing comments and line breaks. */
function contentEnd(node: YamlNode): number {
  if ((isMap(node) || isSeq(node)) && !node.flow && node.items.length > 0) {
    const last = node.items[node.items.length - 1];
    if (isMap(node)) {
      const pair = last as Pair<YamlNode, YamlNode>;
      return contentEnd(pair.value ?? pair.key);
    }
    return contentEnd(last as YamlNode);
  }
  return node.range?.[1] ?? 0;
}

function splice(source: string, from: number, to: number, text: string): string {
  return source.slice(0, from) + text + source.slice(to);
}

/** Inserts lines after the line where {@code index} is, adding a final line break when needed. */
function insertLines(source: string, index: number, lines: string): string {
  const at = lineEnd(source, index);
  const prefix = at === source.length && !source.endsWith("\n") ? "\n" : "";
  return source.slice(0, at) + prefix + lines + "\n" + source.slice(at);
}

/** Removes whole lines from the line of {@code from} to the line of {@code to - 1}. */
function removeLines(source: string, from: number, to: number): string {
  return source.slice(0, lineStart(source, from)) + source.slice(lineEnd(source, to));
}

type Edit = (node: YAMLMap, source: string) => string;

function edit(source: string, nodeId: string, change: Edit): string {
  const document = parseDocument(source, PARSE);
  if (document.errors.length > 0) return source;
  const node = findNode(document, nodeId);
  if (!node) return source;
  if (node.flow) {
    // a node written as { id: ..., type: ... } is rewritten in block style, once
    const rewritten = blockNode(node, source);
    return edit(rewritten, nodeId, change);
  }
  return change(node, source);
}

/** Rewrites a flow node in block style, keeping its keys and their order. */
function blockNode(node: YAMLMap, source: string): string {
  const [start, end] = [node.range?.[0] ?? 0, node.range?.[1] ?? 0];
  const json = node.toJSON() as Record<string, unknown>;
  return splice(source, start, end, blockText(json, column(source, start), true));
}

function keyColumn(node: YAMLMap, source: string): number {
  const first = node.items[0] as Pair<YamlNode, YamlNode> | undefined;
  return first?.key?.range ? column(source, first.key.range[0]) : 2;
}

/** Sets option {@code key} to {@code text} (already in YAML form), or removes it when undefined. */
function setPair(node: YAMLMap, source: string, key: string, text: string | undefined): string {
  const pair = pairOf(node, key);
  if (pair) {
    const keyStart = pair.key.range?.[0] ?? 0;
    const end = pair.value ? contentEnd(pair.value) : (pair.key.range?.[1] ?? keyStart);
    if (text === undefined) return removeLines(source, keyStart, end);
    const colon = source.indexOf(":", pair.key.range?.[1] ?? keyStart);
    return splice(source, colon + 1, end, " " + text);
  }
  if (text === undefined) return source;
  return insertLines(source, contentEnd(node), " ".repeat(keyColumn(node, source)) + key + ": " + text);
}

/** Sets a node option: a scalar, a list (written in flow style) or nothing to remove it. */
export function setOption(
  source: string,
  nodeId: string,
  key: OptionKey,
  value: string | string[] | undefined,
): string {
  return edit(source, nodeId, (node, text) => {
    if (Array.isArray(value)) {
      return setPair(node, text, key, value.length === 0 ? undefined : listText(value));
    }
    return setPair(node, text, key, value === undefined ? undefined : flowText(value));
  });
}

/** Adds an entry at the end of {@code mapping}, creating the list when there is none. */
export function addEntry(source: string, nodeId: string, entry: MappingEntry): string {
  return edit(source, nodeId, (node, text) => {
    const pair = pairOf(node, "mapping");
    const list = pair?.value;
    if (isSeq(list) && !list.flow && list.items.length > 0) {
      const last = list.items[list.items.length - 1] as YamlNode;
      const start = last.range?.[0] ?? 0;
      const dash = text.slice(lineStart(text, start), start).replace(/-\s*$/, "");
      return insertLines(text, contentEnd(last), dash + "- " + flowText(plain(entry)));
    }
    const entries = isSeq(list) ? list.items.map(entryOf) : [];
    return writeMapping(node, text, [...entries, entry]);
  });
}

/** Writes the whole mapping list in block style with one flow entry per line. */
function writeMapping(node: YAMLMap, source: string, entries: MappingEntry[]): string {
  if (entries.length === 0) return setPair(node, source, "mapping", undefined);
  const indent = " ".repeat(keyColumn(node, source) + 2);
  const lines = entries.map((e) => indent + "- " + flowText(plain(e))).join("\n");
  const pair = pairOf(node, "mapping");
  if (pair) {
    const keyStart = pair.key.range?.[0] ?? 0;
    const end = pair.value ? contentEnd(pair.value) : (pair.key.range?.[1] ?? keyStart);
    const colon = source.indexOf(":", pair.key.range?.[1] ?? keyStart);
    return splice(source, colon + 1, end, "\n" + lines);
  }
  return insertLines(source, contentEnd(node), " ".repeat(keyColumn(node, source)) + "mapping:\n" + lines);
}

/** Replaces entry {@code index}, keeping the style it was written in and the comments around it. */
export function setEntry(source: string, nodeId: string, index: number, entry: MappingEntry): string {
  return edit(source, nodeId, (node, text) => {
    const list = pairOf(node, "mapping")?.value;
    if (!isSeq(list) || index < 0 || index >= list.items.length) return text;
    if (list.flow) {
      const entries = list.items.map(entryOf);
      entries[index] = entry;
      return writeMapping(node, text, entries);
    }
    const item = list.items[index] as YamlNode;
    const start = item.range?.[0] ?? 0;
    const end = contentEnd(item);
    const replacement =
      isMap(item) && !item.flow ? blockText(plain(entry), column(text, start)) : flowText(plain(entry));
    return splice(text, start, end, replacement);
  });
}

/** Removes entry {@code index}; removing the last one removes {@code mapping}. */
export function removeEntry(source: string, nodeId: string, index: number): string {
  return edit(source, nodeId, (node, text) => {
    const list = pairOf(node, "mapping")?.value;
    if (!isSeq(list) || index < 0 || index >= list.items.length) return text;
    if (list.items.length === 1) return setPair(node, text, "mapping", undefined);
    if (list.flow) {
      const entries = list.items.map(entryOf);
      entries.splice(index, 1);
      return writeMapping(node, text, entries);
    }
    const item = list.items[index] as YamlNode;
    return removeLines(text, item.range?.[0] ?? 0, contentEnd(item));
  });
}
