/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useMemo, useState, type ReactNode } from "react";
import { useComponent } from "@/api/queries";
import { readNodeOptions, setNodeOption } from "@/features/field-mapping/yamlEdit";
import { useT } from "@/i18n";
import { Button, DropdownMenu, Icon, OptionForm, type OptionField } from "@/ui";
import { LoadingPanel } from "@/ui/LoadingPanel";
import type { StructureNode } from "./structure";

/**
 * The options of one node of the job, from its component's option rule, edited in place: each
 * change is written into the definition at the node, keeping the rest of the text as written.
 */
export function OptionsPanel({
  structure,
  definition,
  node,
  plugin,
  actions,
  onNode,
  onChange,
}: {
  structure: StructureNode[];
  definition: string;
  node: string | null;
  /** The plugin of the node from the last plan, when there is one. */
  plugin: string | undefined;
  actions?: ReactNode;
  onNode: (node: string) => void;
  onChange: (definition: string) => void;
}) {
  const t = useT();
  const [showDetails, setShowDetails] = useState(false);
  const chosen = structure.find((n) => n.id === node) ?? structure[0];
  const component = useComponent(chosen?.kind, chosen?.type || undefined, plugin);
  const options = useMemo(() => (component.data?.options ?? []) as OptionField[], [component.data]);
  const values = useMemo(
    () =>
      chosen
        ? readNodeOptions(
            definition,
            chosen.id,
            options.map((o) => o.key),
          )
        : {},
    [definition, chosen, options],
  );
  if (!chosen) return <p className="p-4 text-sm text-fg-muted">{t("editor.optionEditor.noNodes")}</p>;
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="editor-inspector-node">
        <DropdownMenu
          label={t("editor.optionEditor.node")}
          align="start"
          value={chosen.id}
          onSelect={onNode}
          items={structure.map((n) => ({ id: n.id, label: `${n.id} (${n.type || "?"})` }))}
          trigger={(props) => (
            <Button
              {...props}
              variant="ghost"
              className="editor-inspector-selector justify-start"
              aria-label={t("editor.optionEditor.node")}
            >
              <span className="editor-node-icon" data-kind={chosen.kind}>
                <Icon name={chosen.kind} size={17} />
              </span>
              <span className="min-w-0">
                <span className="block truncate font-semibold text-fg">{chosen.id}</span>
                <code className="block truncate text-xs text-fg-muted">{chosen.type || "?"}</code>
              </span>
              <Icon name="chevronDown" size={14} className="ml-auto shrink-0" />
            </Button>
          )}
        />
        {actions}
      </div>
      <div className="relative min-h-0 flex-1 overflow-auto px-3 pb-3">
        {component.isPending && chosen.type ? (
          <LoadingPanel className="min-h-24" />
        ) : component.isError || !chosen.type ? (
          <p className="text-sm text-fg-muted">{t("editor.optionEditor.unknown", { type: chosen.type || "?" })}</p>
        ) : (
          <>
            {options.length > 0 ? (
              <div className="editor-options-heading">
                <h3>{t("editor.optionEditor.tab")}</h3>
                <Button
                  size="sm"
                  variant="ghost"
                  aria-pressed={showDetails}
                  onClick={() => setShowDetails((value) => !value)}
                >
                  {t("editor.optionEditor.details")}
                </Button>
              </div>
            ) : null}
            <OptionForm
              className="editor-option-fields"
              showDetails={showDetails}
              options={options}
              values={values}
              onChange={(key, value) => onChange(setNodeOption(definition, chosen.id, key, value))}
              empty={t("plugins.noOptions")}
              labels={{
                requirement: {
                  required: t("plugins.requirement.required"),
                  "one-of": t("plugins.requirement.one-of"),
                  conditional: t("plugins.requirement.conditional"),
                  optional: t("plugins.requirement.optional"),
                },
                defaultValue: t("plugins.default"),
                range: t("plugins.range"),
                values: t("plugins.values"),
                sensitive: t("plugins.sensitive"),
                deprecated: t("plugins.deprecated"),
                since: t("plugins.since"),
                editing: {
                  field: t("editor.optionEditor.field"),
                  unset: t("editor.optionEditor.unset"),
                  editInText: t("editor.optionEditor.editInText"),
                  notANumber: t("editor.optionEditor.notANumber"),
                  notAnInteger: t("editor.optionEditor.notAnInteger"),
                  outOfRange: t("editor.optionEditor.outOfRange"),
                },
              }}
            />
          </>
        )}
      </div>
    </div>
  );
}
