/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { MapperOptions, MappingEntry } from "./yamlEdit";

/** A column of a table in the plan. */
export interface SchemaColumn {
  name: string;
  type: string;
  nullable?: boolean;
}

/** Where the value of an output column comes from. */
export type RowSource = "column" | "expression" | "constant";

/**
 * An output column as the field mapper derives it: from an entry of {@code mapping}, or an input
 * column kept by {@code others: keep}. Mirrors the rules of the plugin (reference/mapping).
 */
export interface MappingRow {
  /** The output name. */
  name: string;
  /** The entry it comes from, or null for a kept column. */
  entry: number | null;
  source: RowSource;
  /** The input column, resolved from {@code from} or the kept column. */
  input?: string;
  /** A {@code from} that names no input column. */
  missing?: string;
  expr?: string;
  value?: string;
  /** The declared type, or undefined to keep the type of the input or expression. */
  type?: string;
}

/** What happens to an input column. */
export type InputState = "kept" | "mapped" | "dropped" | "unused";

/** The words of a name: split at {@code _ - . } and spaces and at changes of case. */
export function words(name: string): string[] {
  const out: string[] = [];
  let word = "";
  for (let i = 0; i < name.length; i++) {
    const c = name.charAt(i);
    if (c === "_" || c === "-" || c === " " || c === ".") {
      if (word !== "") out.push(word);
      word = "";
      continue;
    }
    if (word !== "" && isUpper(c)) {
      const previous = word.charAt(word.length - 1);
      const nextLower = i + 1 < name.length && isLower(name.charAt(i + 1));
      if (isLower(previous) || isDigit(previous) || (isUpper(previous) && nextLower)) {
        out.push(word);
        word = "";
      }
    }
    word += c;
  }
  if (word !== "") out.push(word);
  return out;
}

const isUpper = (c: string) => c !== c.toLowerCase() && c === c.toUpperCase();
const isLower = (c: string) => c !== c.toUpperCase() && c === c.toLowerCase();
const isDigit = (c: string) => c >= "0" && c <= "9";

/** {@code name} in the style of {@code name-case}. */
export function applyCase(style: MapperOptions["nameCase"], name: string): string {
  const parts = words(name);
  if (parts.length === 0) return name;
  switch (style) {
    case "keep":
      return name;
    case "lower":
      return name.toLowerCase();
    case "upper":
      return name.toUpperCase();
    case "snake":
      return parts.join("_").toLowerCase();
    case "camel":
      return parts
        .map((w, i) => (i === 0 ? w.toLowerCase() : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()))
        .join("");
  }
}

/** The input column a name stands for: the exact name, or the only one that differs in case. */
export function findColumn(columns: SchemaColumn[], name: string): SchemaColumn | undefined {
  const exact = columns.find((c) => c.name === name);
  if (exact) return exact;
  const loose = columns.filter((c) => c.name.toLowerCase() === name.toLowerCase());
  return loose.length === 1 ? loose[0] : undefined;
}

function sourceOf(entry: MappingEntry): RowSource {
  return entry.value !== undefined ? "constant" : entry.expr !== undefined ? "expression" : "column";
}

/** The output rows of {@code options} for an input table with {@code columns}. */
export function deriveRows(options: MapperOptions, columns: SchemaColumn[]): MappingRow[] {
  const dropped = new Set(
    options.drop.map((d) => findColumn(columns, d)?.name).filter((n): n is string => n !== undefined),
  );
  const consumed = new Set<string>(dropped);
  const entryRow = (entry: MappingEntry, index: number): MappingRow => {
    const input = entry.from !== undefined ? findColumn(columns, entry.from) : undefined;
    return {
      name: entry.to ?? applyCase(options.nameCase, input?.name ?? entry.from ?? ""),
      entry: index,
      source: sourceOf(entry),
      input: input?.name,
      missing: entry.from !== undefined && !input ? entry.from : undefined,
      expr: entry.expr,
      value: entry.value,
      type: entry.type,
    };
  };
  const rows = options.entries.map(entryRow);
  for (const row of rows) if (row.input) consumed.add(row.input);
  const keep = options.others === "keep";
  const kept = (column: SchemaColumn): MappingRow => ({
    name: applyCase(options.nameCase, column.name),
    entry: null,
    source: "column",
    input: column.name,
  });
  const visible = rows.filter((r) => !(r.missing && options.unmapped === "drop"));
  if (options.order === "mapping") {
    return [...visible, ...(keep ? columns.filter((c) => !consumed.has(c.name)).map(kept) : [])];
  }
  const ordered: MappingRow[] = [];
  for (const column of columns) {
    const mapped = visible.filter((r) => r.input === column.name && options.entries[r.entry ?? -1]?.from !== undefined);
    if (mapped.length > 0) ordered.push(...mapped);
    else if (keep && !consumed.has(column.name)) ordered.push(kept(column));
  }
  return [...ordered, ...visible.filter((r) => !r.input || options.entries[r.entry ?? -1]?.from === undefined)];
}

/** What becomes of each input column. */
export function inputStates(
  options: MapperOptions,
  columns: SchemaColumn[],
  rows: MappingRow[],
): Map<string, InputState> {
  const states = new Map<string, InputState>();
  for (const column of columns) {
    const dropped = options.drop.some((d) => findColumn(columns, d)?.name === column.name);
    const row = rows.find((r) => r.input === column.name);
    states.set(column.name, dropped ? "dropped" : !row ? "unused" : row.entry === null ? "kept" : "mapped");
  }
  return states;
}

/** A name that no output column has: {@code base}, or {@code base_2}, {@code base_3}, ... */
export function freeName(rows: MappingRow[], base: string): string {
  const taken = new Set(rows.map((r) => r.name));
  if (!taken.has(base)) return base;
  for (let i = 2; ; i++) if (!taken.has(`${base}_${i}`)) return `${base}_${i}`;
}

// ------------------------------------------------------------------ types

/** How a value changes when a column of type {@code from} is converted to {@code to}. */
export type Compatibility = "same" | "widens" | "narrows" | "converts";

interface ParsedType {
  root: string;
  args: number[];
}

const INTEGERS = ["INT8", "INT16", "INT32", "INT64"];
const DIGITS: Record<string, number> = { INT8: 3, INT16: 5, INT32: 10, INT64: 19 };

function parseType(text: string): ParsedType {
  const clean = text
    .replace(/\s+NOT\s+NULL\s*$/i, "")
    .trim()
    .toUpperCase();
  const match = /^([A-Z_0-9]+)\s*(?:\(([^)]*)\))?/.exec(clean);
  const root = match?.[1] ?? clean;
  const args = (match?.[2] ?? "")
    .split(",")
    .map((a) => Number(a.trim()))
    .filter((n) => !Number.isNaN(n) && match?.[2] !== undefined);
  return { root: root === "VARCHAR" ? "STRING" : root, args };
}

/** Classifies converting {@code from} to {@code to}, written as logical types. */
export function compatibility(from: string, to: string): Compatibility {
  const a = parseType(from);
  const b = parseType(to);
  if (a.root === b.root && a.args.join() === b.args.join()) return "same";
  if (INTEGERS.includes(a.root) && INTEGERS.includes(b.root)) {
    return INTEGERS.indexOf(b.root) >= INTEGERS.indexOf(a.root) ? "widens" : "narrows";
  }
  if (INTEGERS.includes(a.root) && b.root === "DECIMAL") {
    const [p = 38, s = 0] = b.args;
    return p - s >= (DIGITS[a.root] ?? 19) ? "widens" : "narrows";
  }
  if (a.root === "DECIMAL" && b.root === "DECIMAL") {
    const [p1 = 38, s1 = 0] = a.args;
    const [p2 = 38, s2 = 0] = b.args;
    return p2 - s2 >= p1 - s1 && s2 >= s1 ? "widens" : "narrows";
  }
  if (a.root === "FLOAT32" && b.root === "FLOAT64") return "widens";
  if (a.root === b.root && (a.root === "STRING" || a.root === "BYTES")) {
    const [n1] = a.args;
    const [n2] = b.args;
    return n2 === undefined || (n1 !== undefined && n2 >= n1) ? "widens" : "narrows";
  }
  if (a.root === b.root && (a.root === "TIMESTAMP" || a.root === "TIMESTAMP_LTZ" || a.root === "TIME")) {
    return (b.args[0] ?? 6) >= (a.args[0] ?? 6) ? "widens" : "narrows";
  }
  if (a.root === "DATE" && b.root === "TIMESTAMP") return "widens";
  if ((a.root === "FLOAT64" || a.root === "FLOAT32" || a.root === "DECIMAL") && INTEGERS.includes(b.root)) {
    return "narrows";
  }
  return "converts";
}
