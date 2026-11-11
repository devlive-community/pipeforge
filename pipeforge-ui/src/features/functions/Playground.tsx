/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { ApiError, type EvaluationResult } from "@pipeforge/client";
import { useMutation } from "@tanstack/react-query";
import { useCallback, useMemo } from "react";
import { api } from "@/api/client";
import { useT } from "@/i18n";
import { Badge, Button, Card, IconButton, Input, type EditorCompletion, type EditorDiagnostic } from "@/ui";
import { CodeEditor } from "@/ui/CodeEditor";
import { expressionCompletion, type CompletableColumn, type CompletableFunction } from "./completion";

export interface PlaygroundColumn extends CompletableColumn {
  value: string;
}

export interface PlaygroundState {
  expression: string;
  columns: PlaygroundColumn[];
  timezone: string;
}

export const INITIAL_PLAYGROUND: PlaygroundState = {
  expression: "UPPER(name) || ' pays ' || CAST(amount * 2 AS STRING)",
  columns: [
    { name: "name", type: "STRING", value: "pipeforge" },
    { name: "amount", type: "DECIMAL(10, 2)", value: "12.50" },
  ],
  timezone: "",
};

/** Tries an expression on one row: columns with types and values, evaluated by the server. */
export function Playground({
  state,
  onChange,
  functions,
}: {
  state: PlaygroundState;
  onChange: (state: PlaygroundState) => void;
  functions: CompletableFunction[];
}) {
  const t = useT();
  const evaluation = useMutation<EvaluationResult, Error>({
    mutationFn: () =>
      api.evaluateExpression({
        expression: state.expression,
        columns: state.columns.map((c) => ({
          name: c.name,
          type: c.type,
          value: c.value === "" ? undefined : c.value,
        })),
        timezone: state.timezone || undefined,
      }),
  });

  const completion: EditorCompletion = useCallback(
    (text, position, explicit) => expressionCompletion(text, position, explicit, functions, state.columns),
    [functions, state.columns],
  );

  const error = evaluation.error instanceof ApiError ? evaluation.error : null;
  const diagnostics = useMemo((): EditorDiagnostic[] => {
    const params = (error?.info.params ?? {}) as Record<string, unknown>;
    return error?.code === "PF-PLAN-0007" && typeof params.line === "number"
      ? [{ line: params.line, column: Number(params.column) || 1, severity: "error", message: error.message }]
      : [];
  }, [error]);

  const setColumn = (index: number, change: Partial<PlaygroundColumn>) =>
    onChange({ ...state, columns: state.columns.map((c, i) => (i === index ? { ...c, ...change } : c)) });

  return (
    <Card title={t("functions.playground")} description={t("functions.playgroundHint")}>
      <div className="h-28 overflow-hidden rounded-control border border-border">
        <CodeEditor
          language="sql"
          label={t("functions.expression")}
          value={state.expression}
          onChange={(expression) => onChange({ ...state, expression })}
          completion={completion}
          diagnostics={diagnostics}
        />
      </div>
      <h3 className="mt-4 text-xs font-medium text-fg-muted">{t("functions.columns")}</h3>
      <div className="mt-1 space-y-2">
        {state.columns.map((column, index) => (
          <fieldset key={index} className="grid grid-cols-[1fr_1fr_1fr_auto] items-end gap-2">
            <legend className="sr-only">{t("functions.column", { index: index + 1 })}</legend>
            <Input
              label={t("functions.name")}
              value={column.name}
              onChange={(e) => setColumn(index, { name: e.target.value })}
            />
            <Input
              label={t("functions.type")}
              value={column.type}
              onChange={(e) => setColumn(index, { type: e.target.value })}
            />
            <Input
              label={t("functions.value")}
              placeholder={t("functions.valueHint")}
              value={column.value}
              onChange={(e) => setColumn(index, { value: e.target.value })}
            />
            <IconButton
              icon="trash"
              label={t("functions.removeColumn", { name: column.name || String(index + 1) })}
              onClick={() => onChange({ ...state, columns: state.columns.filter((_, i) => i !== index) })}
            />
          </fieldset>
        ))}
      </div>
      <div className="mt-2 flex flex-wrap items-end justify-between gap-2">
        <Button
          size="sm"
          variant="ghost"
          icon="plus"
          onClick={() => onChange({ ...state, columns: [...state.columns, { name: "", type: "STRING", value: "" }] })}
        >
          {t("functions.addColumn")}
        </Button>
        <div className="flex items-end gap-2">
          <Input
            label={t("functions.timezone")}
            placeholder={t("functions.timezoneHint")}
            value={state.timezone}
            onChange={(e) => onChange({ ...state, timezone: e.target.value })}
            className="w-40"
          />
          <Button variant="primary" icon="play" loading={evaluation.isPending} onClick={() => evaluation.mutate()}>
            {t("functions.evaluate")}
          </Button>
        </div>
      </div>
      <section
        aria-live="polite"
        aria-label={t("functions.result")}
        className="mt-4 min-h-16 rounded-control bg-surface-2 p-3"
      >
        {evaluation.data ? (
          <div className="space-y-1 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-fg-muted">{t("functions.resultType")}</span>
              <span className="font-mono text-fg">{evaluation.data.type}</span>
              {evaluation.data.deterministic ? (
                <Badge>{t("functions.deterministic")}</Badge>
              ) : (
                <Badge tone="warning">{t("functions.nondeterministic")}</Badge>
              )}
            </div>
            <div className="flex items-center gap-2">
              <span className="text-fg-muted">{t("functions.resultValue")}</span>
              {evaluation.data.value === null || evaluation.data.value === undefined ? (
                <Badge tone="info">{t("functions.null")}</Badge>
              ) : (
                <span className="break-all font-mono text-fg">{evaluation.data.value}</span>
              )}
            </div>
          </div>
        ) : evaluation.error ? (
          <div className="space-y-1 text-sm">
            <div className="flex items-center gap-2">
              <Badge tone="danger">{error?.code ?? "Error"}</Badge>
            </div>
            <p className="break-words text-fg">{evaluation.error.message}</p>
            {error?.info.hint ? <p className="text-fg-muted">{error.info.hint}</p> : null}
          </div>
        ) : null}
      </section>
    </Card>
  );
}
