/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { components } from "@pipeforge/client";
import { useSearchParams } from "react-router";
import { useComponent, usePlugins } from "@/api/queries";
import { useT } from "@/i18n";
import { Badge, Button, Card, Empty, OptionForm, PageHeader, Skeleton, type OptionField } from "@/ui";

type Kind = "source" | "transform" | "sink";
type Plugin = components["schemas"]["PluginInfo"];
const KINDS: string[] = ["source", "transform", "sink"];

function PluginCard({
  plugin,
  chosen,
  onChoose,
}: {
  plugin: Plugin;
  chosen: string;
  onChoose: (type: Kind, identifier: string) => void;
}) {
  const t = useT();
  const coordinate = `${plugin.id}@${plugin.version}`;
  return (
    <Card
      title={plugin.name}
      description={<span className="font-mono text-xs">{coordinate}</span>}
      actions={plugin.license ? <Badge>{plugin.license}</Badge> : null}
    >
      {plugin.description ? <p className="text-sm text-fg">{plugin.description}</p> : null}
      <div className="mt-2 space-y-1 text-xs text-fg-muted">
        {plugin.vendor ? (
          <p>
            {t("plugins.vendor")}: {plugin.vendor}
          </p>
        ) : null}
        {plugin.datasource ? (
          <p>
            {t("plugins.datasource", {
              type: plugin.datasource.type ?? "",
              versions: (plugin.datasource.versions ?? []).join(", "),
            })}
          </p>
        ) : null}
        {plugin.drivers && plugin.drivers.length > 0 ? (
          <p>{t("plugins.drivers", { drivers: plugin.drivers.join(", ") })}</p>
        ) : null}
      </div>
      <h3 className="mt-3 text-xs font-medium text-fg-muted">{t("plugins.components")}</h3>
      <div className="mt-1 flex flex-wrap gap-1.5">
        {(plugin.components ?? []).map((component) => {
          const kind = component.type as Kind;
          const key = `${coordinate}/${kind}/${component.identifier}`;
          return KINDS.includes(kind) ? (
            <Button
              key={key}
              size="sm"
              variant={chosen === key ? "primary" : "secondary"}
              aria-pressed={chosen === key}
              onClick={() => onChoose(kind, component.identifier ?? "")}
            >
              {t(`plugins.kinds.${kind}`)} {component.identifier}
            </Button>
          ) : (
            <Badge key={key}>
              {component.type} {component.identifier}
            </Badge>
          );
        })}
      </div>
    </Card>
  );
}

function ComponentDetails({ type, identifier, plugin }: { type: Kind; identifier: string; plugin: string }) {
  const t = useT();
  const component = useComponent(type, identifier, plugin);
  const info = component.data;
  return (
    <Card
      title={t("plugins.options", { component: `${t(`plugins.kinds.${type}`)} ${identifier}` })}
      description={plugin}
    >
      {component.isPending ? (
        <div className="space-y-2">
          <Skeleton className="h-5 w-2/3" />
          <Skeleton className="h-5 w-1/2" />
        </div>
      ) : component.isError ? (
        <p className="text-sm text-danger">{(component.error as Error).message}</p>
      ) : info ? (
        <div className="space-y-3">
          <OptionForm
            options={(info.options ?? []) as OptionField[]}
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
            }}
          />
          <ul className="space-y-1 text-sm text-fg-muted">
            {(info.exclusive ?? []).map((group) => (
              <li key={group.join()}>{t("plugins.exclusive", { options: group.join(", ") })}</li>
            ))}
            {(info.conditions ?? []).map((c) => (
              <li key={`${c.option}=${String(c.value)}`}>
                {t("plugins.condition", {
                  option: c.option ?? "",
                  value: String(c.value),
                  required: (c.required ?? []).join(", "),
                })}
              </li>
            ))}
            {info.nativeNamespaces && info.nativeNamespaces.length > 0 ? (
              <li>{t("plugins.nativeNamespaces", { namespaces: info.nativeNamespaces.join(", ") })}</li>
            ) : null}
          </ul>
        </div>
      ) : null}
    </Card>
  );
}

/** Installed plugins and the options of their components; the chosen component is in the address. */
export function PluginsPage() {
  const t = useT();
  const plugins = usePlugins();
  const [params, setParams] = useSearchParams();
  const type = params.get("type") as Kind | null;
  const identifier = params.get("component");
  const plugin = params.get("plugin");
  const chosen = type && identifier && plugin ? `${plugin}/${type}/${identifier}` : "";
  const list = plugins.data?.plugins ?? [];
  const unavailable = plugins.data?.unavailable ?? [];

  return (
    <>
      <PageHeader title={t("plugins.title")} description={t("plugins.description")} />
      {plugins.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : list.length === 0 ? (
        <Card>
          <Empty icon="plug" title={t("plugins.none")} description={t("plugins.noneHint")} />
        </Card>
      ) : (
        <div className="grid items-start gap-4 lg:grid-cols-2">
          <div className="space-y-4">
            {list.map((p) => (
              <PluginCard
                key={`${p.id}@${p.version}`}
                plugin={p}
                chosen={chosen}
                onChoose={(kind, id) => setParams({ type: kind, component: id, plugin: `${p.id}@${p.version}` })}
              />
            ))}
          </div>
          <div className="lg:sticky lg:top-4">
            {type && identifier && plugin ? (
              <ComponentDetails type={type} identifier={identifier} plugin={plugin} />
            ) : (
              <Card>
                <Empty icon="plug" title={t("plugins.choose")} />
              </Card>
            )}
          </div>
        </div>
      )}
      {unavailable.length > 0 ? (
        <Card title={t("plugins.unavailableTitle")} className="mt-4">
          <ul className="divide-y divide-border text-sm">
            {unavailable.map((item) => (
              <li key={item.location} className="py-2">
                <div className="font-mono text-xs text-fg">{item.location}</div>
                <div className="mt-0.5 text-fg-muted">{item.reason}</div>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}
    </>
  );
}
