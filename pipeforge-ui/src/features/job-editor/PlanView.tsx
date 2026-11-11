/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { components } from "@pipeforge/client";
import { useT } from "@/i18n";
import { Badge, DescriptionList, Table, type BadgeTone } from "@/ui";

type Plan = components["schemas"]["ExplainDocument"];
type PlanNode = components["schemas"]["Node"];
type PlanTable = components["schemas"]["Table"];

const ORIGIN_TONES: Record<string, BadgeTone> = {
  node: "primary",
  env: "info",
  cluster: "warning",
  default: "neutral",
};

function display(value: unknown): string {
  if (value === null || value === undefined) return "";
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

function TableSchema({ title, table }: { title: string; table: PlanTable }) {
  const t = useT();
  return (
    <div className="mt-3">
      <div className="text-sm font-medium text-fg">
        {title}
        {table.primaryKey && table.primaryKey.length > 0 ? (
          <span className="ml-2 text-xs font-normal text-fg-muted">
            {t("editor.primaryKey", { columns: table.primaryKey.join(", ") })}
          </span>
        ) : null}
      </div>
      <Table
        caption={title}
        rowKey={(c) => c.name ?? ""}
        rows={table.columns ?? []}
        columns={[
          { key: "name", header: t("editor.column"), cell: (c) => <span className="font-mono text-xs">{c.name}</span> },
          {
            key: "type",
            header: t("editor.columnType"),
            cell: (c) => <span className="font-mono text-xs">{c.type}</span>,
          },
          {
            key: "nullable",
            header: t("editor.nullable"),
            cell: (c) => (c.nullable ? t("editor.yes") : t("editor.no")),
          },
          { key: "comment", header: "", cell: (c) => <span className="text-fg-muted">{c.comment}</span> },
        ]}
      />
    </div>
  );
}

function NodeDetails({ node }: { node: PlanNode }) {
  const t = useT();
  const id = node.id ?? "";
  const options = (node.options ?? []).filter((o) => o.origin !== "default");
  const tables = node.kind === "sink" ? (node.inputTables ?? []) : (node.outputTables ?? []);
  return (
    <section className="border-t border-border py-3" aria-label={id}>
      <h3 className="text-sm font-semibold text-fg">{t("editor.options", { node: id })}</h3>
      <Table
        caption={t("editor.optionsCaption", { node: id })}
        rowKey={(o) => o.key ?? ""}
        rows={options}
        empty={t("editor.noOptions")}
        columns={[
          { key: "key", header: t("editor.key"), cell: (o) => <span className="font-mono text-xs">{o.key}</span> },
          {
            key: "value",
            header: t("editor.value"),
            cell: (o) => <span className="font-mono text-xs">{display(o.value)}</span>,
          },
          {
            key: "origin",
            header: t("editor.origin"),
            cell: (o) => (
              <Badge tone={ORIGIN_TONES[o.origin ?? ""] ?? "neutral"}>
                {t(`editor.origins.${(o.origin ?? "default") as "node" | "env" | "cluster" | "default"}`)}
              </Badge>
            ),
          },
        ]}
      />
      {tables.map((table) => (
        <TableSchema
          key={table.id}
          table={table}
          title={t(node.kind === "sink" ? "editor.receives" : "editor.schemaOf", { node: id, table: table.id ?? "" })}
        />
      ))}
    </section>
  );
}

/** The plan of a valid job: settings, variables, nodes, their options and schemas, warnings. */
export function PlanView({ plan }: { plan: Plan }) {
  const t = useT();
  const job = plan.job;
  const variables = Object.entries(plan.variables ?? {});
  return (
    <div className="space-y-4 p-4">
      <DescriptionList
        items={[
          { label: t("editor.jobName"), value: job?.name },
          { label: t("editor.mode"), value: job?.modeDerived ? `${job.mode} (${t("editor.derived")})` : job?.mode },
          { label: t("editor.delivery"), value: job?.delivery },
          { label: t("editor.engine"), value: job?.engine },
          { label: t("editor.parallelism"), value: job?.parallelism },
          { label: t("editor.timezone"), value: job?.timezone },
          {
            label: t("editor.variables"),
            value: variables.length === 0 ? t("editor.none") : variables.map(([k, v]) => `${k}=${v}`).join("  "),
          },
        ]}
      />
      <Table
        caption={t("editor.nodes")}
        rowKey={(n) => n.id ?? ""}
        rows={plan.nodes ?? []}
        columns={[
          { key: "id", header: t("editor.node"), cell: (n) => <span className="font-mono text-xs">{n.id}</span> },
          {
            key: "kind",
            header: t("editor.kind"),
            cell: (n) => t(`editor.kinds.${n.kind as "source" | "transform" | "sink"}`),
          },
          { key: "type", header: t("editor.type"), cell: (n) => n.type },
          {
            key: "plugin",
            header: t("editor.plugin"),
            cell: (n) => <span className="font-mono text-xs">{n.plugin}</span>,
          },
          { key: "parallelism", header: t("editor.parallelism"), cell: (n) => n.parallelism },
          {
            key: "inputs",
            header: t("editor.inputs"),
            cell: (n) =>
              n.kind === "source"
                ? t(n.boundedness === "unbounded" ? "editor.unbounded" : "editor.bounded")
                : `${(n.inputs ?? []).join(", ")}${n.inputsImplied ? ` (${t("editor.implied")})` : ""}`,
          },
        ]}
      />
      <div>
        {(plan.nodes ?? []).map((node) => (
          <NodeDetails key={node.id} node={node} />
        ))}
      </div>
      {plan.warnings && plan.warnings.length > 0 ? (
        <div>
          <h3 className="text-sm font-semibold text-fg">{t("editor.warnings")}</h3>
          <ul className="mt-1 list-disc pl-5 text-sm text-fg-muted">
            {plan.warnings.map((w) => (
              <li key={w}>{w}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
