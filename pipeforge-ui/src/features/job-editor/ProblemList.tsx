/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useT } from "@/i18n";
import { Badge, Button, Empty, Icon } from "@/ui";
import type { Problem } from "./problems";

/** The problems of the job; problems with a line can be jumped to. */
export function ProblemList({ problems, onGoTo }: { problems: Problem[]; onGoTo: (line: number) => void }) {
  const t = useT();
  if (problems.length === 0) {
    return <Empty icon="circleCheck" title={t("editor.noProblems")} />;
  }
  return (
    <ul className="divide-y divide-border">
      {problems.map((problem, index) => (
        <li key={index} className="flex items-start gap-3 px-4 py-3">
          <Icon
            name={problem.severity === "error" ? "alert" : "info"}
            className={problem.severity === "error" ? "mt-0.5 shrink-0 text-danger" : "mt-0.5 shrink-0 text-warning"}
          />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={problem.severity === "error" ? "danger" : "warning"}>
                {t(problem.severity === "error" ? "editor.error" : "editor.warning")}
              </Badge>
              {problem.code ? <span className="font-mono text-xs text-fg-muted">{problem.code}</span> : null}
            </div>
            <p className="mt-1 break-words text-sm text-fg">{problem.message}</p>
            {problem.hint ? (
              <p className="mt-0.5 text-sm text-fg-muted">{t("editor.hint", { hint: problem.hint })}</p>
            ) : null}
          </div>
          {problem.line !== undefined ? (
            <Button size="sm" variant="ghost" onClick={() => onGoTo(problem.line as number)}>
              {t("editor.goToLine", { line: problem.line })}
            </Button>
          ) : null}
        </li>
      ))}
    </ul>
  );
}
