/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { settingEntries, type SettingEntry } from "@/lib/generated";
import type { Lang } from "@/lib/i18n";

const TEXT = {
  "en-US": {
    setting: "Setting",
    type: "Type",
    default: "Default",
    meaning: "Meaning",
    required: "required",
    none: "none",
    env: "Environment variable",
  },
  "zh-CN": {
    setting: "设置",
    type: "类型",
    default: "默认值",
    meaning: "含义",
    required: "必填",
    none: "无",
    env: "环境变量",
  },
} as const;

const FILES = ["conf/pipeforge.yaml", "conf/master.yaml", "conf/worker.yaml"];

/** One table per configuration file; every setting row has its key as anchor. */
export function ConfigurationTable({ entries, lang = "en-US" }: { entries: SettingEntry[]; lang?: Lang }) {
  const text = TEXT[lang] ?? TEXT["en-US"];
  return (
    <div className="space-y-8">
      {FILES.map((file) => {
        const rows = entries.filter((entry) => entry.file === file);
        if (rows.length === 0) return null;
        return (
          <section key={file} aria-label={file}>
            <h3 id={file.replace("conf/", "").replace(".yaml", "-yaml")}>
              <code>{file}</code>
            </h3>
            <div className="overflow-x-auto">
              <table>
                <thead>
                  <tr>
                    <th>{text.setting}</th>
                    <th>{text.type}</th>
                    <th>{text.default}</th>
                    <th>{text.meaning}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((entry) => (
                    <tr key={entry.key} id={entry.key} className="scroll-mt-24">
                      <td>
                        <code>{entry.key}</code>
                      </td>
                      <td>{entry.type}</td>
                      <td>
                        {entry.required ? (
                          text.required
                        ) : entry.derived ? (
                          (entry.derived[lang] ?? entry.derived["en-US"])
                        ) : entry.default ? (
                          <code>{entry.default}</code>
                        ) : (
                          text.none
                        )}
                      </td>
                      <td>
                        {entry.description[lang] ?? entry.description["en-US"] ?? ""}{" "}
                        <span className="text-fg-muted">
                          {text.env}: <code>{entry.env}</code>
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        );
      })}
    </div>
  );
}

/** Every setting of the cluster processes, generated from the code. */
export function ConfigurationList({ lang = "en-US" }: { lang?: Lang }) {
  return <ConfigurationTable entries={settingEntries()} lang={lang} />;
}
