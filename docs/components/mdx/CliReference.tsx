/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { cliReference, type CliOption, type CliReferenceData } from "@/lib/generated";
import type { Lang } from "@/lib/i18n";

const TEXT = {
  "en-US": {
    global: "Global options",
    globalIntro: "Every command accepts these options, before or after its name.",
    commands: "Commands",
    usage: "Usage",
    arguments: "Arguments",
    options: "Options",
    option: "Option",
    argument: "Argument",
    meaning: "Meaning",
    defaultValue: "Default",
    repeatable: "repeatable",
    required: "required",
    exitCodes: "Exit codes",
    code: "Code",
  },
  "zh-CN": {
    global: "全局选项",
    globalIntro: "每个命令都接受这些选项，写在命令名之前或之后均可。",
    commands: "命令",
    usage: "用法",
    arguments: "参数",
    options: "选项",
    option: "选项",
    argument: "参数",
    meaning: "含义",
    defaultValue: "默认值",
    repeatable: "可重复",
    required: "必填",
    exitCodes: "退出码",
    code: "退出码",
  },
} as const;

function text<T extends string>(values: Record<string, T>, lang: Lang): T | string {
  return values[lang] ?? values["en-US"] ?? "";
}

function OptionTable({ options, lang }: { options: CliOption[]; lang: Lang }) {
  const t = TEXT[lang] ?? TEXT["en-US"];
  return (
    <div className="overflow-x-auto">
      <table>
        <thead>
          <tr>
            <th>{t.option}</th>
            <th>{t.meaning}</th>
            <th>{t.defaultValue}</th>
          </tr>
        </thead>
        <tbody>
          {options.map((option) => (
            <tr key={option.names.join(",")}>
              <td>
                <code>
                  {option.names.join(", ")}
                  {option.label ? ` ${option.label}` : ""}
                </code>
                {option.repeatable ? <span className="text-fg-muted"> ({t.repeatable})</span> : null}
              </td>
              <td>{text(option.description, lang)}</td>
              <td>{option.default ? <code>{option.default}</code> : "–"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** The commands of the CLI, their options and the exit codes; every command has the anchor `cmd-<name>`. */
export function CliTable({ data, lang = "en-US" }: { data: CliReferenceData; lang?: Lang }) {
  const t = TEXT[lang] ?? TEXT["en-US"];
  return (
    <div className="space-y-10">
      <section aria-label={t.global}>
        <h2 id="global-options">{t.global}</h2>
        <p>{t.globalIntro}</p>
        <OptionTable options={data.globalOptions} lang={lang} />
      </section>
      <section aria-label={t.commands} className="space-y-8">
        <h2 id="commands">{t.commands}</h2>
        {data.commands.map((command) => (
          <section key={command.name} aria-label={command.name}>
            <h3 id={command.anchor} className="scroll-mt-24">
              <code>pipeforge {command.name}</code>
            </h3>
            <p>{text(command.description, lang)}</p>
            <pre className="overflow-x-auto">
              <code>{command.synopsis}</code>
            </pre>
            {command.arguments.length > 0 ? (
              <div className="overflow-x-auto">
                <table>
                  <thead>
                    <tr>
                      <th>{t.argument}</th>
                      <th>{t.meaning}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {command.arguments.map((argument) => (
                      <tr key={argument.label}>
                        <td>
                          <code>
                            {argument.label}
                            {argument.repeatable ? "..." : ""}
                          </code>
                          {argument.required ? <span className="text-fg-muted"> ({t.required})</span> : null}
                        </td>
                        <td>{text(argument.description, lang)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : null}
            {command.options.length > 0 ? <OptionTable options={command.options} lang={lang} /> : null}
          </section>
        ))}
      </section>
      <section aria-label={t.exitCodes}>
        <h2 id="exit-codes">{t.exitCodes}</h2>
        <div className="overflow-x-auto">
          <table>
            <thead>
              <tr>
                <th>{t.code}</th>
                <th>{t.meaning}</th>
              </tr>
            </thead>
            <tbody>
              {data.exitCodes.map((exit) => (
                <tr key={exit.code} id={`exit-${exit.code}`}>
                  <td>
                    <code>{exit.code}</code>
                  </td>
                  <td>{text(exit.description, lang)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

/** The CLI reference, generated from the command definitions. */
export function CliReference({ lang = "en-US" }: { lang?: Lang }) {
  return <CliTable data={cliReference()} lang={lang} />;
}
