/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useState } from "react";
import { useT } from "@/i18n";
import { Button, Icon, Input, LoadingPanel, SegmentedControl } from "@/ui";
import { COMPONENT_DRAG } from "./graphEdit";
import type { SchemaInfo } from "./schema";
import { KINDS, type NodeKind, type StructureNode } from "./structure";

export function EditorLibrary({
  catalog,
  loading,
  nodes,
  selected,
  onAdd,
  onSelect,
  compactOpen,
}: {
  catalog: SchemaInfo["types"] | undefined;
  loading: boolean;
  nodes: StructureNode[];
  selected: string | undefined;
  onAdd: (kind: NodeKind, type: string) => void;
  onSelect: (id: string) => void;
  compactOpen: boolean;
}) {
  const t = useT();
  const [mode, setMode] = useState("components");
  const [filter, setFilter] = useState("");
  const query = filter.trim().toLowerCase();
  const components = mode === "components";
  const entries = components
    ? KINDS.flatMap((kind) => (catalog?.[kind] ?? []).map((type) => ({ kind, id: type, type })))
    : nodes;
  const visible = entries.filter((entry) => `${entry.id} ${entry.type}`.toLowerCase().includes(query));

  return (
    <aside
      className="editor-library"
      id="editor-component-library"
      data-compact-open={compactOpen}
      aria-label={t("editor.library")}
    >
      <div className="editor-pane-title">
        <SegmentedControl
          label={t("editor.library")}
          segments={[
            { id: "components", label: t("editor.components") },
            { id: "nodes", label: t("editor.nodeList") },
          ]}
          value={mode}
          onChange={setMode}
        />
      </div>
      <div className="editor-library-body">
        <Input
          label={t("editor.searchNodes")}
          hideLabel
          icon="search"
          placeholder={t("editor.searchNodes")}
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
        />
        {loading && components ? <LoadingPanel className="min-h-24" /> : null}
        {!loading && components && !catalog ? (
          <p className="mt-4 text-sm text-fg-muted">{t("editor.componentsUnavailable")}</p>
        ) : null}
        {visible.length === 0 && (!components || catalog) ? (
          <p className="mt-4 text-sm text-fg-muted">{t("editor.noMatchingNodes")}</p>
        ) : null}
        {KINDS.map((kind) => {
          const group = visible.filter((entry) => entry.kind === kind);
          if (group.length === 0) return null;
          return (
            <section key={kind} className="editor-library-group">
              <h3>{t(`editor.kinds.${kind}`)}</h3>
              {group.map((entry, index) => (
                <Button
                  key={`${entry.id}-${index}`}
                  variant="ghost"
                  className="editor-library-node justify-start"
                  data-kind={kind}
                  draggable={components}
                  aria-label={components ? t("editor.addComponent", { type: entry.type }) : undefined}
                  aria-pressed={components ? undefined : entry.id === selected}
                  onClick={() => (components ? onAdd(kind, entry.type) : onSelect(entry.id))}
                  onDragStart={(event) => {
                    if (!components) return;
                    event.dataTransfer.effectAllowed = "copy";
                    event.dataTransfer.setData(COMPONENT_DRAG, JSON.stringify([kind, entry.type]));
                  }}
                >
                  <span className="editor-node-icon">
                    <Icon name={kind} size={15} />
                  </span>
                  <span className="min-w-0">
                    <span className="block truncate font-semibold">{entry.id}</span>
                    {!components ? (
                      <code className="block truncate font-mono text-xs text-fg-muted">{entry.type || "?"}</code>
                    ) : null}
                  </span>
                </Button>
              ))}
            </section>
          );
        })}
      </div>
    </aside>
  );
}
