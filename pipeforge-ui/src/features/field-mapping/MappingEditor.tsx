/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { components } from "@pipeforge/client";
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { useT } from "@/i18n";
import {
  Badge,
  type BadgeTone,
  Button,
  cx,
  DropdownMenu,
  Empty,
  IconButton,
  Input,
  RowButton,
  SegmentedControl,
} from "@/ui";
import {
  applyCase,
  compatibility,
  deriveRows,
  findColumn,
  freeName,
  inputStates,
  type Compatibility,
  type MappingRow,
  type RowSource,
  type SchemaColumn,
} from "./model";
import {
  addEntry,
  readMapper,
  removeEntry,
  setEntry,
  setOption,
  type MapperOptions,
  type MappingEntry,
} from "./yamlEdit";

type PlanNode = components["schemas"]["Node"];

const COMPAT_TONES: Record<Compatibility, BadgeTone> = {
  same: "success",
  widens: "success",
  narrows: "warning",
  converts: "info",
};

/** An input that commits its value on Enter or when it loses the focus. */
function CommitInput({
  value,
  onCommit,
  mono = false,
  ...rest
}: {
  label: string;
  value: string;
  onCommit: (value: string) => void;
  mono?: boolean;
  placeholder?: string;
}) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  return (
    <Input
      {...rest}
      value={draft}
      className={mono ? "[&_input]:font-mono" : undefined}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => {
        if (draft !== value) onCommit(draft);
      }}
      onKeyDown={(e) => {
        if (e.key === "Enter") (e.target as HTMLInputElement).blur();
        if (e.key === "Escape") setDraft(value);
      }}
    />
  );
}

interface Line {
  key: string;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

/**
 * The mapping of one field mapper: its input columns on the left, its output columns on the
 * right, joined by lines. Changes are written back into the YAML of the job with small edits, so
 * comments and formatting stay as they are; the plan then shows the new output schema.
 */
export function MappingEditor({
  source,
  nodeId,
  node,
  stale,
  onChange,
}: {
  source: string;
  nodeId: string;
  node: PlanNode | undefined;
  stale: boolean;
  onChange: (source: string) => void;
}) {
  const t = useT();
  const options = useMemo(() => readMapper(source, nodeId), [source, nodeId]);
  const tables = node?.inputTables ?? [];
  const [tableIndex, setTableIndex] = useState(0);
  const input = tables[Math.min(tableIndex, Math.max(0, tables.length - 1))];
  const columns: SchemaColumn[] = useMemo(
    () => (input?.columns ?? []).map((c) => ({ name: c.name ?? "", type: c.type ?? "", nullable: c.nullable })),
    [input],
  );
  const output = node?.outputTables?.find((o) => o.id === input?.id);
  const rows = useMemo(() => (options ? deriveRows(options, columns) : []), [options, columns]);
  const states = useMemo(() => (options ? inputStates(options, columns, rows) : new Map()), [options, columns, rows]);
  const [selected, setSelected] = useState<string | null>(null);
  const row = rows.find((r) => r.name === selected) ?? null;

  if (!options) return null;
  if (!node) {
    return <Empty icon="code" title={t("mapping.needsPlan")} />;
  }
  if (!input) {
    return <Empty icon="code" title={t("mapping.noTables")} />;
  }

  const typeOf = (name: string) => output?.columns?.find((c) => c.name === name)?.type;
  const apply = (change: (text: string) => string, select?: string) => {
    const next = change(source);
    if (next !== source) onChange(next);
    if (select !== undefined) setSelected(select);
  };

  /** The entry a row stands for: its own, or a new one that takes the kept column. */
  const entryOf = (r: MappingRow): MappingEntry =>
    r.entry !== null ? { ...options.entries[r.entry] } : { from: r.input };
  const commit = (r: MappingRow, change: Partial<MappingEntry>) => {
    const entry: MappingEntry = { ...entryOf(r), ...change };
    for (const key of Object.keys(entry) as (keyof MappingEntry)[]) {
      if (entry[key] === undefined || (entry[key] === "" && key !== "value")) delete entry[key];
    }
    const name = entry.to ?? applyCase(options.nameCase, entry.from ?? r.name);
    apply((text) => (r.entry !== null ? setEntry(text, nodeId, r.entry, entry) : addEntry(text, nodeId, entry)), name);
  };

  const connect = (from: string, target: string) => {
    if (target === "new") {
      const kept = states.get(from) === "kept";
      const name = kept ? applyCase(options.nameCase, from) : freeName(rows, applyCase(options.nameCase, from));
      apply((text) => addEntry(text, nodeId, kept ? { from } : { from, to: name }), name);
      return;
    }
    const r = rows.find((x) => x.name === target);
    if (!r || r.input === from) return;
    if (r.entry !== null) {
      const entry = { ...options.entries[r.entry], from, to: options.entries[r.entry]?.to ?? r.name };
      delete entry.expr;
      delete entry.value;
      apply((text) => setEntry(text, nodeId, r.entry ?? 0, entry), r.name);
    } else {
      const replaced = r.input;
      apply(
        (text) =>
          setOption(addEntry(text, nodeId, { from, to: r.name }), nodeId, "drop", [
            ...options.drop,
            ...(replaced ? [replaced] : []),
          ]),
        r.name,
      );
    }
  };

  const setDrop = (column: string, drop: boolean) =>
    apply((text) =>
      setOption(
        text,
        nodeId,
        "drop",
        drop ? [...options.drop, column] : options.drop.filter((d) => findColumn(columns, d)?.name !== column),
      ),
    );

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-2">
        {tables.length > 1 ? (
          <SegmentedControl
            label={t("mapping.table")}
            value={String(tableIndex)}
            onChange={(id) => setTableIndex(Number(id))}
            segments={tables.map((table, i) => ({ id: String(i), label: table.id ?? String(i) }))}
          />
        ) : null}
        <div className="flex items-center gap-2">
          <span className="text-xs text-fg-muted">{t("mapping.others")}</span>
          <SegmentedControl
            label={t("mapping.others")}
            value={options.others}
            onChange={(value) =>
              apply((text) => setOption(text, nodeId, "others", value === "keep" ? undefined : value))
            }
            segments={[
              { id: "keep", label: t("mapping.othersKeep") },
              { id: "drop", label: t("mapping.othersDrop") },
            ]}
          />
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-fg-muted">{t("mapping.nameCase")}</span>
          <SegmentedControl
            label={t("mapping.nameCase")}
            value={options.nameCase}
            onChange={(value) =>
              apply((text) => setOption(text, nodeId, "name-case", value === "keep" ? undefined : value))
            }
            segments={(["keep", "lower", "upper", "snake", "camel"] as const).map((id) => ({
              id,
              label: t(`mapping.cases.${id}`),
            }))}
          />
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-fg-muted">{t("mapping.order")}</span>
          <SegmentedControl
            label={t("mapping.order")}
            value={options.order}
            onChange={(value) =>
              apply((text) => setOption(text, nodeId, "order", value === "source" ? undefined : value))
            }
            segments={[
              { id: "source", label: t("mapping.orders.source") },
              { id: "mapping", label: t("mapping.orders.mapping") },
            ]}
          />
        </div>
        {stale ? <Badge tone="warning">{t("mapping.stale")}</Badge> : null}
      </div>
      <div className="grid min-h-0 flex-1 gap-4 overflow-auto p-4 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <Connections
          columns={columns}
          rows={rows}
          states={states}
          typeOf={typeOf}
          selected={selected}
          onSelect={setSelected}
          onConnect={connect}
          onDrop={setDrop}
        />
        {row ? (
          <Details
            key={row.name}
            row={row}
            options={options}
            columns={columns}
            outputType={typeOf(row.name)}
            onCommit={(change) => commit(row, change)}
            onRemove={() =>
              row.entry !== null
                ? apply((text) => removeEntry(text, nodeId, row.entry ?? 0), "")
                : row.input && setDrop(row.input, true)
            }
          />
        ) : (
          <p className="text-sm text-fg-muted">{t("mapping.chooseRow")}</p>
        )}
      </div>
      <div className="flex items-center justify-between gap-2 border-t border-border px-4 py-2 text-xs text-fg-muted">
        <span>{t("mapping.dragHint")}</span>
        <Button
          size="sm"
          icon="plus"
          onClick={() => {
            const name = freeName(rows, "new_column");
            apply((text) => addEntry(text, nodeId, { value: "", to: name }), name);
          }}
        >
          {t("mapping.addColumn")}
        </Button>
      </div>
    </div>
  );
}

/** The two columns of the mapping and the lines between them; input columns are dragged onto outputs. */
function Connections({
  columns,
  rows,
  states,
  typeOf,
  selected,
  onSelect,
  onConnect,
  onDrop,
}: {
  columns: SchemaColumn[];
  rows: MappingRow[];
  states: Map<string, string>;
  typeOf: (name: string) => string | undefined;
  selected: string | null;
  onSelect: (name: string) => void;
  onConnect: (from: string, target: string) => void;
  onDrop: (column: string, drop: boolean) => void;
}) {
  const t = useT();
  const area = useRef<HTMLDivElement>(null);
  const [lines, setLines] = useState<Line[]>([]);
  const [drag, setDrag] = useState<{ from: string; x1: number; y1: number; x2: number; y2: number } | null>(null);

  useLayoutEffect(() => {
    const element = area.current;
    if (!element) return;
    const measure = () => {
      const box = element.getBoundingClientRect();
      const next: Line[] = [];
      for (const r of rows) {
        if (!r.input) continue;
        const left = element.querySelector<HTMLElement>(`[data-input="${escape(r.input)}"]`);
        const right = element.querySelector<HTMLElement>(`[data-output="${escape(r.name)}"]`);
        if (!left || !right) continue;
        const a = left.getBoundingClientRect();
        const b = right.getBoundingClientRect();
        next.push({
          key: `${r.input}->${r.name}`,
          x1: a.right - box.left,
          y1: a.top + a.height / 2 - box.top,
          x2: b.left - box.left,
          y2: b.top + b.height / 2 - box.top,
        });
      }
      setLines(next);
    };
    measure();
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(measure);
    observer?.observe(element);
    return () => observer?.disconnect();
  }, [rows, columns]);

  const startDrag = (e: ReactPointerEvent<HTMLElement>, from: string) => {
    const element = area.current;
    if (!element || e.button !== 0) return;
    e.preventDefault();
    const box = element.getBoundingClientRect();
    const item = e.currentTarget.getBoundingClientRect();
    const x1 = item.right - box.left;
    const y1 = item.top + item.height / 2 - box.top;
    setDrag({ from, x1, y1, x2: e.clientX - box.left, y2: e.clientY - box.top });
    const scroller = scrollParent(element);
    const move = (event: PointerEvent) => {
      // near the top or the bottom of the list, scroll it so that every column can be reached
      if (scroller) {
        const edges = scroller.getBoundingClientRect();
        if (event.clientY < edges.top + EDGE) scroller.scrollBy(0, -STEP);
        else if (event.clientY > edges.bottom - EDGE) scroller.scrollBy(0, STEP);
      }
      const now = element.getBoundingClientRect();
      setDrag((d) => (d ? { ...d, x2: event.clientX - now.left, y2: event.clientY - now.top } : d));
    };
    const up = (event: PointerEvent) => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      setDrag(null);
      const target = document.elementFromPoint?.(event.clientX, event.clientY)?.closest<HTMLElement>("[data-target]");
      if (target?.dataset.target) onConnect(from, target.dataset.target);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };

  return (
    <div ref={area} className="relative grid grid-cols-[minmax(0,1fr)_3rem_minmax(0,1fr)] items-start">
      <section aria-label={t("mapping.inputs")}>
        <h3 className="mb-2 text-xs font-medium text-fg-muted">{t("mapping.inputs")}</h3>
        <ul className="space-y-1.5">
          {columns.map((column) => {
            const state = states.get(column.name) ?? "unused";
            const dropped = state === "dropped";
            return (
              <li
                key={column.name}
                data-input={column.name}
                onPointerDown={(e) => startDrag(e, column.name)}
                className={cx(
                  "flex h-9 cursor-grab touch-none select-none items-center gap-2 rounded-control border px-2 text-sm",
                  "transition-colors duration-fast ease-out",
                  dropped
                    ? "border-dashed border-border text-fg-subtle"
                    : "border-border bg-surface hover:border-primary",
                )}
              >
                <span className={cx("min-w-0 flex-1 truncate font-mono", dropped && "line-through")}>
                  {column.name}
                </span>
                <span className="truncate font-mono text-xs text-fg-muted">{column.type}</span>
                <span className="sr-only">{t(`mapping.states.${state as "kept"}`)}</span>
                <IconButton
                  size="sm"
                  icon={dropped ? "undo" : "trash"}
                  label={t(dropped ? "mapping.restore" : "mapping.drop", { column: column.name })}
                  onPointerDown={(e) => e.stopPropagation()}
                  onClick={() => onDrop(column.name, !dropped)}
                />
              </li>
            );
          })}
        </ul>
      </section>
      <svg aria-hidden="true" className="pointer-events-none absolute inset-0 h-full w-full overflow-visible">
        {lines.map((line) => (
          <path
            key={line.key}
            d={curve(line)}
            className={cx(
              "fill-none stroke-[1.5] transition-colors duration-fast ease-out",
              line.key.endsWith(`->${selected}`) ? "stroke-primary" : "stroke-fg-subtle",
            )}
          />
        ))}
        {drag ? <path d={curve(drag)} className="fill-none stroke-primary stroke-2 [stroke-dasharray:4_3]" /> : null}
      </svg>
      <div />
      <section aria-label={t("mapping.outputs")}>
        <h3 className="mb-2 text-xs font-medium text-fg-muted">{t("mapping.outputs")}</h3>
        <ul className="space-y-1.5">
          {rows.map((r) => (
            <li key={`${r.entry ?? "k"}-${r.name}`}>
              <RowButton
                data-output={r.name}
                data-target={r.name}
                selected={selected === r.name}
                highlighted={drag !== null}
                aria-label={t("mapping.rowLabel", { name: r.name, type: typeOf(r.name) ?? r.type ?? "?" })}
                onClick={() => onSelect(r.name)}
              >
                <RowSummary row={r} type={typeOf(r.name)} columns={columns} />
              </RowButton>
            </li>
          ))}
          <li>
            <div
              data-target="new"
              className={cx(
                "flex h-9 items-center justify-center rounded-control border border-dashed text-xs text-fg-muted",
                drag ? "border-primary text-fg" : "border-border",
              )}
            >
              {t("mapping.newColumn")}
            </div>
          </li>
        </ul>
      </section>
    </div>
  );
}

/** How close to the edge of the list the pointer scrolls it while dragging, and by how much. */
const EDGE = 32;
const STEP = 16;

/** The nearest ancestor that scrolls vertically. */
function scrollParent(element: HTMLElement): HTMLElement | null {
  for (let node = element.parentElement; node; node = node.parentElement) {
    const overflow = getComputedStyle(node).overflowY;
    if ((overflow === "auto" || overflow === "scroll") && node.scrollHeight > node.clientHeight) return node;
  }
  return null;
}

/** A value for an attribute selector. */
function escape(value: string): string {
  return typeof CSS !== "undefined" && CSS.escape ? CSS.escape(value) : value.replace(/["\\]/g, "\\$&");
}

function curve(line: { x1: number; y1: number; x2: number; y2: number }): string {
  const mid = (line.x1 + line.x2) / 2;
  return `M ${line.x1} ${line.y1} C ${mid} ${line.y1}, ${mid} ${line.y2}, ${line.x2} ${line.y2}`;
}

function RowSummary({ row, type, columns }: { row: MappingRow; type: string | undefined; columns: SchemaColumn[] }) {
  const t = useT();
  const input = row.input ? findColumn(columns, row.input) : undefined;
  const compat = row.type && input ? compatibility(input.type, row.type) : null;
  const badges = [
    row.missing ? (
      <Badge key="missing" tone="danger">
        {t("mapping.missing", { column: row.missing })}
      </Badge>
    ) : null,
    row.source !== "column" ? (
      <Badge key="source" tone="info">
        {t(`mapping.sources.${row.source}`)}
      </Badge>
    ) : null,
    compat && compat !== "same" ? (
      <Badge key="compat" tone={COMPAT_TONES[compat]}>
        {t(`mapping.compat.${compat}`)}
      </Badge>
    ) : null,
  ].filter(Boolean);
  return (
    <>
      <span className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="truncate font-mono">{row.name}</span>
        {badges.length > 0 ? <span className="flex flex-wrap gap-1">{badges}</span> : null}
      </span>
      <span className="shrink-0 font-mono text-xs text-fg-muted">{type ?? row.type ?? "—"}</span>
    </>
  );
}

/** The fields of one output column. */
function Details({
  row,
  options,
  columns,
  outputType,
  onCommit,
  onRemove,
}: {
  row: MappingRow;
  options: MapperOptions;
  columns: SchemaColumn[];
  outputType: string | undefined;
  onCommit: (change: Partial<MappingEntry>) => void;
  onRemove: () => void;
}) {
  const t = useT();
  const entry: MappingEntry = row.entry !== null ? (options.entries[row.entry] ?? {}) : { from: row.input };
  const input = row.input ? findColumn(columns, row.input) : undefined;
  const compat = entry.type && input ? compatibility(input.type, entry.type) : null;
  const setSource = (source: RowSource) => {
    if (source === row.source) return;
    if (source === "constant") onCommit({ value: "", expr: undefined, from: undefined, to: row.name });
    else if (source === "expression") {
      onCommit({ expr: row.input ? row.input : "NULL", value: undefined, to: row.name });
    } else {
      const first = row.input ?? columns[0]?.name;
      onCommit({ from: first, expr: undefined, value: undefined, to: row.name });
    }
  };
  return (
    <section aria-label={t("mapping.details", { name: row.name })} className="space-y-3">
      <h3 className="font-mono text-sm font-semibold text-fg">{row.name}</h3>
      <CommitInput label={t("mapping.name")} value={row.name} mono onCommit={(to) => to && onCommit({ to })} />
      <SegmentedControl
        label={t("mapping.source")}
        value={row.source}
        onChange={(id) => setSource(id as RowSource)}
        segments={(["column", "expression", "constant"] as const).map((id) => ({
          id,
          label: t(`mapping.sources.${id}`),
        }))}
      />
      {row.source === "column" ? (
        <div className="flex items-center gap-2 text-sm">
          <span className="text-xs font-medium text-fg-muted">{t("mapping.inputColumn")}</span>
          <DropdownMenu
            label={t("mapping.inputColumn")}
            align="start"
            value={row.input}
            items={columns.map((c) => ({ id: c.name, label: c.name }))}
            onSelect={(from) => onCommit({ from, to: row.name })}
            trigger={(props) => (
              <Button {...props} size="sm" className="font-mono">
                {row.input ?? row.missing ?? "—"}
              </Button>
            )}
          />
        </div>
      ) : row.source === "expression" ? (
        <CommitInput
          label={t("mapping.expression")}
          value={entry.expr ?? ""}
          mono
          onCommit={(expr) => onCommit({ expr: expr || "NULL" })}
        />
      ) : (
        <CommitInput
          label={t("mapping.value")}
          value={entry.value ?? ""}
          mono
          onCommit={(value) => onCommit({ value })}
        />
      )}
      <div className="flex items-end gap-2">
        <div className="min-w-0 flex-1">
          <CommitInput
            label={t("mapping.type")}
            value={entry.type ?? ""}
            mono
            placeholder={outputType ? t("mapping.typeHint", { type: outputType }) : undefined}
            onCommit={(type) => onCommit({ type: type || undefined })}
          />
        </div>
        {compat ? <Badge tone={COMPAT_TONES[compat]}>{t(`mapping.compat.${compat}`)}</Badge> : null}
      </div>
      <SegmentedControl
        label={t("mapping.nullable")}
        value={entry.nullable === undefined ? "derived" : entry.nullable ? "yes" : "no"}
        onChange={(id) => onCommit({ nullable: id === "derived" ? undefined : id === "yes" })}
        segments={(["derived", "yes", "no"] as const).map((id) => ({ id, label: t(`mapping.nullability.${id}`) }))}
      />
      <CommitInput
        label={t("mapping.default")}
        value={entry.default ?? ""}
        mono
        onCommit={(value) => onCommit({ default: value || undefined })}
      />
      <CommitInput
        label={t("mapping.comment")}
        value={entry.comment ?? ""}
        onCommit={(comment) => onCommit({ comment: comment || undefined })}
      />
      <Button size="sm" variant="danger" icon="trash" onClick={onRemove}>
        {t(row.entry !== null ? "mapping.remove" : "mapping.dropColumn")}
      </Button>
    </section>
  );
}
