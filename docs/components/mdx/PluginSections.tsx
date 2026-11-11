/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";
import { pluginEntry, type PluginComponent, type PluginEntry, type PluginOption } from "@/lib/generated";
import type { Lang } from "@/lib/i18n";

// The generated sections of plugin pages, rendered from the documentation data each plugin exports
// (src/generated/plugin-docs/<id>.json). Pages contain only the component in these sections.

const TEXT = {
  "en-US": {
    missing: (id: string) => `No documentation data for plugin '${id}'. Run the docs goal of the plugin.`,
    component: "Component",
    capabilities: "Capabilities",
    noCapabilities: "No optional capabilities",
    system: "System",
    versions: "Verified versions",
    noSystem: "No external system: the plugin runs inside the engine, on every engine version.",
    option: "Option",
    type: "Type",
    default: "Default",
    required: "Required",
    description: "Description",
    requirement: { required: "yes", optional: "no", "one-of": "one of a group", conditional: "conditional" },
    range: "range",
    values: "values",
    oldNames: "old names",
    since: "since",
    sensitive: "sensitive, masked everywhere",
    noOptions: "The component has no options.",
    exclusive: (keys: string) => `Set exactly one of ${keys}.`,
    condition: (option: string, value: string, keys: string) => `When ${option} is ${value}, also set ${keys}.`,
    namespaces: "Native configuration namespaces",
    noNative: "The plugin passes no native configuration to a client library.",
    read: "Reading",
    write: "Writing",
    logical: "Logical type",
    external: "External type",
    note: "Note",
    noMapping: "The plugin uses the logical types as they are; there is no type mapping.",
    noHistory: "No history is available in this build.",
  },
  "zh-CN": {
    missing: (id: string) => `没有插件 '${id}' 的文档数据。请运行该插件的 docs 目标。`,
    component: "组件",
    capabilities: "能力",
    noCapabilities: "没有可选能力",
    system: "系统",
    versions: "已验证版本",
    noSystem: "不连接外部系统：插件在引擎内运行，适用于所有引擎版本。",
    option: "选项",
    type: "类型",
    default: "默认值",
    required: "必填",
    description: "说明",
    requirement: { required: "是", optional: "否", "one-of": "一组中选一个", conditional: "满足条件时" },
    range: "范围",
    values: "可选值",
    oldNames: "旧名称",
    since: "起始版本",
    sensitive: "敏感项，在所有位置遮蔽",
    noOptions: "该组件没有选项。",
    exclusive: (keys: string) => `${keys} 中必须且只能设置一个。`,
    condition: (option: string, value: string, keys: string) => `${option} 为 ${value} 时，还需要设置 ${keys}。`,
    namespaces: "原生配置命名空间",
    noNative: "该插件不向客户端库透传原生配置。",
    read: "读取",
    write: "写入",
    logical: "逻辑类型",
    external: "外部类型",
    note: "说明",
    noMapping: "该插件直接使用逻辑类型，没有类型映射。",
    noHistory: "本次构建没有可用的提交历史。",
  },
} as const;

type Text = (typeof TEXT)[Lang];

interface Props {
  plugin: string;
  component?: string;
  lang?: Lang;
}

function withPlugin(props: Props, render: (plugin: PluginEntry, text: Text) => ReactNode) {
  const text = TEXT[props.lang ?? "en-US"];
  const plugin = pluginEntry(props.plugin);
  if (!plugin) return <p role="alert">{text.missing(props.plugin)}</p>;
  return render(plugin, text);
}

function components(plugin: PluginEntry, type?: string): PluginComponent[] {
  return plugin.components.filter((c) => !type || c.type === type);
}

function show(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "string") return value;
  return JSON.stringify(value);
}

function Table({ head, rows }: { head: string[]; rows: ReactNode[][] }) {
  return (
    <div className="overflow-x-auto">
      <table>
        <thead>
          <tr>
            {head.map((cell) => (
              <th key={cell}>{cell}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i}>
              {row.map((cell, j) => (
                <td key={j}>{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** The components of the plugin and the optional capabilities each implements. */
export function PluginCapabilities(props: Props) {
  return withPlugin(props, (plugin, text) => (
    <Table
      head={[text.component, text.capabilities]}
      rows={components(plugin, props.component).map((c) => [
        <code key="id">{`${c.type} ${c.identifier}`}</code>,
        c.capabilities.length === 0 ? text.noCapabilities : c.capabilities.join(", "),
      ])}
    />
  ));
}

/** The external system and the versions the plugin is verified against. */
export function PluginVersions(props: Props) {
  return withPlugin(props, (plugin, text) =>
    plugin.datasource ? (
      <Table
        head={[text.system, text.versions]}
        rows={[[plugin.datasource.type, plugin.datasource.versions.join(", ")]]}
      />
    ) : (
      <p>{text.noSystem}</p>
    ),
  );
}

function constraints(option: PluginOption, text: Text): string {
  const parts: string[] = [];
  if (option.min !== null || option.max !== null) parts.push(`${text.range} ${show(option.min)} … ${show(option.max)}`);
  if (option.values.length > 0) parts.push(`${text.values}: ${option.values.join(", ")}`);
  if (option.deprecatedKeys.length > 0) parts.push(`${text.oldNames}: ${option.deprecatedKeys.join(", ")}`);
  if (option.since) parts.push(`${text.since} ${option.since}`);
  if (option.sensitive) parts.push(text.sensitive);
  return parts.join("; ");
}

/** Every option of the component with its type, default, requirement and constraints. */
export function PluginOptions(props: Props) {
  return withPlugin(props, (plugin, text) => (
    <div className="space-y-6">
      {components(plugin, props.component).map((component) => {
        const options = component.options ?? [];
        return (
          <section key={component.identifier + component.type} aria-label={`${component.type} ${component.identifier}`}>
            {options.length === 0 ? (
              <p>{text.noOptions}</p>
            ) : (
              <Table
                head={[text.option, text.type, text.default, text.required, text.description]}
                rows={options.map((option) => {
                  const extra = constraints(option, text);
                  return [
                    <code key="key" id={`option-${option.key}`}>
                      {option.key}
                    </code>,
                    option.type,
                    option.sensitive ? "—" : show(option.default),
                    text.requirement[option.requirement],
                    <>
                      {option.description}
                      {extra ? <span className="block text-fg-muted">{extra}</span> : null}
                    </>,
                  ];
                })}
              />
            )}
            {(component.exclusive ?? []).map((keys) => (
              <p key={keys.join()}>{text.exclusive(keys.join(", "))}</p>
            ))}
            {(component.conditions ?? []).map((c) => (
              <p key={c.option + show(c.value)}>{text.condition(c.option, show(c.value), c.required.join(", "))}</p>
            ))}
          </section>
        );
      })}
    </div>
  ));
}

/** The namespaces of native configuration the plugin passes to its client library. */
export function PluginNativeOptions(props: Props) {
  return withPlugin(props, (plugin, text) => {
    const namespaces = [...new Set(plugin.components.flatMap((c) => c.nativeNamespaces ?? []))];
    return namespaces.length === 0 ? (
      <p>{text.noNative}</p>
    ) : (
      <p>
        {text.namespaces}:{" "}
        {namespaces.map((ns, i) => (
          <span key={ns}>
            {i > 0 ? ", " : null}
            <code>{ns}.*</code>
          </span>
        ))}
      </p>
    );
  });
}

/** The type mapping of the plugin, from its type-mapping.yaml. */
export function PluginTypeMapping(props: Props) {
  return withPlugin(props, (plugin, text) => {
    const mapping = plugin.typeMapping;
    if (!mapping || (!mapping.read?.length && !mapping.write?.length)) return <p>{text.noMapping}</p>;
    return (
      <div className="space-y-4">
        {(["read", "write"] as const).map((direction) =>
          mapping[direction]?.length ? (
            <section key={direction} aria-label={text[direction]}>
              <p>{text[direction]}</p>
              <Table
                head={[text.external, text.logical, text.note]}
                rows={(mapping[direction] ?? []).map((e) => [e.external ?? "—", e.logical ?? "—", e.note ?? ""])}
              />
            </section>
          ) : null,
        )}
      </div>
    );
  });
}

/** The commits that changed the plugin, newest first. */
export function PluginChangelog(props: Props) {
  return withPlugin(props, (plugin, text) =>
    plugin.changelog.length === 0 ? (
      <p>{text.noHistory}</p>
    ) : (
      <ul>
        {plugin.changelog.map((entry) => (
          <li key={entry.commit}>
            <code>{entry.commit}</code> {entry.date} — {entry.subject}
          </li>
        ))}
      </ul>
    ),
  );
}
