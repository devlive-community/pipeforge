/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { metricEntries, type MetricEntry } from "@/lib/generated";
import type { Lang } from "@/lib/i18n";

const TEXT = {
  "en-US": {
    job: "Job metrics",
    node: "Node metrics",
    metric: "Metric",
    type: "Type",
    unit: "Unit",
    meaning: "Meaning",
  },
  "zh-CN": {
    job: "作业级指标",
    node: "节点级指标",
    metric: "指标",
    type: "类型",
    unit: "单位",
    meaning: "含义",
  },
} as const;

/** One table per scope; every metric row has the anchor `<scope>-<name>` in lower case. */
export function MetricTable({ entries, lang = "en-US" }: { entries: MetricEntry[]; lang?: Lang }) {
  const text = TEXT[lang] ?? TEXT["en-US"];
  return (
    <div className="space-y-8">
      {(["job", "node"] as const).map((scope) => {
        const rows = entries.filter((entry) => entry.scope === scope);
        if (rows.length === 0) return null;
        return (
          <section key={scope} aria-label={text[scope]}>
            <h3 id={`${scope}-metrics`}>{text[scope]}</h3>
            <div className="overflow-x-auto">
              <table>
                <thead>
                  <tr>
                    <th>{text.metric}</th>
                    <th>{text.type}</th>
                    <th>{text.unit}</th>
                    <th>{text.meaning}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((entry) => (
                    <tr key={entry.name} id={`${scope}-${entry.name}`.toLowerCase()} className="scroll-mt-24">
                      <td>
                        <code>{entry.name}</code>
                      </td>
                      <td>{entry.type}</td>
                      <td>{entry.unit}</td>
                      <td>{entry.description[lang] ?? entry.description["en-US"] ?? ""}</td>
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

/** Every engine metric, generated from the metric catalog. */
export function MetricList({ lang = "en-US" }: { lang?: Lang }) {
  return <MetricTable entries={metricEntries()} lang={lang} />;
}
