/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useT } from "@/i18n";
import { Card } from "@/ui";
import { evidenceValue, isAdviceCode, type RunAnalysis } from "./analysis";

/** What limits a run: the conclusion, its evidence and the advice, from the run analysis. */
export function BottleneckCard({ analysis, className }: { analysis: RunAnalysis | undefined; className?: string }) {
  const t = useT();
  const bottleneck = analysis?.bottleneck;
  return (
    <Card title={t("runDetail.bottleneck")} className={className}>
      {bottleneck ? (
        <div className="space-y-3">
          <div>
            <p className="font-medium text-fg">
              <span>{t(`runDetail.kinds.${bottleneck.kind}`)}</span>
              {bottleneck.node ? (
                <span className="ml-2 font-mono text-sm text-fg-muted">
                  {t("runDetail.at", { node: bottleneck.node })}
                </span>
              ) : null}
            </p>
            <p className="mt-0.5 text-sm text-fg-muted">{t(`runDetail.kindHints.${bottleneck.kind}`)}</p>
          </div>
          {bottleneck.evidence.length > 0 ? (
            <div>
              <h3 className="text-xs font-medium text-fg-muted">{t("runDetail.evidence")}</h3>
              <ul className="mt-1 flex flex-wrap gap-2 text-sm">
                {bottleneck.evidence.map((e) => (
                  <li key={`${e.metric}/${e.node ?? ""}`} className="rounded-control bg-surface-2 px-2 py-0.5">
                    <span className="font-mono text-xs text-fg-muted">
                      {e.node ? `${e.node} · ` : ""}
                      {e.metric}
                    </span>{" "}
                    <span className="tabular-nums text-fg">{evidenceValue(e.metric, e.value)}</span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
          {bottleneck.advice.length > 0 ? (
            <div>
              <h3 className="text-xs font-medium text-fg-muted">{t("runDetail.advice")}</h3>
              <ul className="mt-1 list-disc space-y-0.5 pl-5 text-sm text-fg">
                {bottleneck.advice.map((a) => (
                  <li key={a.code}>
                    {isAdviceCode(a.code)
                      ? t(`runDetail.adviceCodes.${a.code}`, { node: bottleneck.node ?? "" })
                      : a.text}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      ) : (
        <p className="text-sm text-fg-muted">{t("runDetail.noAnalysis")}</p>
      )}
    </Card>
  );
}
