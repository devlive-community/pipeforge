/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useMemo } from "react";
import { useComponent } from "@/api/queries";
import { readNodeOptions, setNodeOption } from "@/features/field-mapping/yamlEdit";
import { useT } from "@/i18n";
import { Button, DropdownMenu, OptionForm, type OptionField } from "@/ui";
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
  onNode,
  onChange,
}: {
  structure: StructureNode[];
  definition: string;
  node: string | null;
  /** The plugin of the node from the last plan, when there is one. */
  plugin: string | undefined;
  onNode: (node: string) => void;
  onChange: (definition: string) => void;
}) {
  const t = useT();
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
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-2">
        <DropdownMenu
          label={t("editor.optionEditor.node")}
          align="start"
          value={chosen.id}
          onSelect={onNode}
          items={structure.map((n) => ({ id: n.id, label: `${n.id} (${n.type || "?"})` }))}
          trigger={(props) => (
            <Button {...props} size="sm" icon="chevronDown" aria-label={t("editor.optionEditor.node")}>
              <span className="font-mono">{`${chosen.id} (${chosen.type || "?"})`}</span>
            </Button>
          )}
        />
        <span className="text-sm text-fg-muted">{t("editor.optionEditor.hint")}</span>
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-4">
        {component.isPending && chosen.type ? (
          <LoadingPanel className="min-h-24" />
        ) : component.isError || !chosen.type ? (
          <p className="text-sm text-fg-muted">{t("editor.optionEditor.unknown", { type: chosen.type || "?" })}</p>
        ) : (
          <OptionForm
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
        )}
      </div>
    </div>
  );
}
