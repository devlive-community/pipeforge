/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useCallback, useMemo, useRef } from "react";
import { useJobSchema } from "@/api/queries";
import { useT } from "@/i18n";
import { CodeEditor } from "@/ui/CodeEditor";
import { usePreferences } from "@/state/preferences";
import {
  Badge,
  Button,
  Card,
  DagCanvas,
  FileButton,
  PageHeader,
  Spinner,
  Splitter,
  Tabs,
  type CodeEditorHandle,
  type DagNode,
  type EditorCompletion,
} from "@/ui";
import { suggest, yamlContext } from "./completion";
import { EXAMPLE_JOB } from "./example";
import { PlanView } from "./PlanView";
import { ProblemList } from "./ProblemList";
import { diagnosticsOf, problemsOf } from "./problems";
import { readSchema } from "./schema";
import { readStructure } from "./structure";
import { useDraft } from "./useDraft";
import { useJobCheck } from "./useJobCheck";

function download(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: "application/yaml" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}

/** The job editor: YAML with completion, live validation, the plan and a picture of the graph. */
export function JobEditorPage() {
  const t = useT();
  const language = usePreferences((s) => s.language);
  const [definition, setDefinition] = useDraft();
  const editor = useRef<CodeEditorHandle>(null);
  const schema = useJobSchema();
  const { checking, validation, plan } = useJobCheck(definition);

  const structure = useMemo(() => readStructure(definition), [definition]);
  const problems = useMemo(() => problemsOf(validation.data, structure), [validation.data, structure]);
  const diagnostics = useMemo(() => diagnosticsOf(problems), [problems]);
  const info = useMemo(() => (schema.data ? readSchema(schema.data, language) : null), [schema.data, language]);

  const completion: EditorCompletion = useCallback(
    (source, position, explicit) => {
      if (!info) return null;
      const where = yamlContext(source, position);
      if (!where) return null;
      const options = suggest(info, where, readStructure(source));
      return options.length === 0 && !explicit ? null : { from: where.from, options };
    },
    [info],
  );

  const failing = new Set(problems.filter((p) => p.severity === "error").map((p) => p.line));
  const nodes: DagNode[] = structure.map((node) => {
    const kind = t(`editor.kinds.${node.kind}`);
    const error = failing.has(node.line);
    return {
      id: node.id,
      kind: node.kind,
      detail: node.type,
      inputs: node.inputs,
      status: error ? "error" : validation.data?.valid ? "ok" : undefined,
      label: t(error ? "editor.nodeError" : "editor.nodeLabel", { id: node.id, kind, type: node.type }),
    };
  });

  const errorCount = problems.filter((p) => p.severity === "error").length;
  const status =
    definition.trim() === "" ? null : checking ? (
      <span className="flex items-center gap-1.5 text-sm text-fg-muted">
        <Spinner size={14} />
        {t("editor.checking")}
      </span>
    ) : validation.data?.valid ? (
      <Badge tone="success">{t("editor.valid")}</Badge>
    ) : validation.data ? (
      <Badge tone="danger">{t("editor.invalid", { count: errorCount })}</Badge>
    ) : null;

  const name = /^name:\s*(\S+)/m.exec(definition)?.[1] ?? "job";
  const goTo = (line: number) => editor.current?.focusLine(line);

  return (
    <div className="flex h-[calc(100dvh-7.5rem)] min-h-[36rem] flex-col">
      <PageHeader
        title={t("editor.title")}
        description={t("editor.description")}
        actions={
          <>
            {status}
            <FileButton accept=".yaml,.yml,.json" onFile={(_, text) => setDefinition(text)}>
              {t("editor.import")}
            </FileButton>
            <Button icon="download" onClick={() => download(`${name}.yaml`, definition)}>
              {t("editor.export")}
            </Button>
            <Button icon="undo" variant="ghost" onClick={() => setDefinition(EXAMPLE_JOB)}>
              {t("editor.reset")}
            </Button>
          </>
        }
      />
      <Card className="flex min-h-0 flex-1 flex-col overflow-hidden p-0">
        <Splitter
          className="min-h-0 flex-[3]"
          label={t("editor.splitter")}
          initial={55}
          first={
            <CodeEditor
              ref={editor}
              value={definition}
              onChange={setDefinition}
              label={t("editor.editorLabel")}
              diagnostics={diagnostics}
              completion={completion}
            />
          }
          second={
            <DagCanvas
              nodes={nodes}
              label={t("editor.graphLabel")}
              labels={{ zoomIn: t("editor.zoomIn"), zoomOut: t("editor.zoomOut"), fit: t("editor.fit") }}
              onSelect={(id) => {
                const line = structure.find((n) => n.id === id)?.line;
                if (line) goTo(line);
              }}
            />
          }
        />
        <Tabs
          className="min-h-0 flex-[2] border-t border-border"
          label={t("editor.panels")}
          tabs={[
            {
              id: "problems",
              label: t("editor.problems"),
              badge:
                problems.length > 0 ? (
                  <Badge tone={errorCount > 0 ? "danger" : "warning"}>{problems.length}</Badge>
                ) : null,
              content: validation.isError ? (
                <p className="p-4 text-sm text-danger">
                  {t("editor.serverError", { message: (validation.error as Error).message })}
                </p>
              ) : definition.trim() === "" ? (
                <p className="p-4 text-sm text-fg-muted">{t("editor.emptyDefinition")}</p>
              ) : !validation.data ? (
                <p className="flex items-center gap-2 p-4 text-sm text-fg-muted">
                  <Spinner size={14} />
                  {t("editor.checking")}
                </p>
              ) : (
                <ProblemList problems={problems} onGoTo={goTo} />
              ),
            },
            {
              id: "plan",
              label: t("editor.plan"),
              content: plan?.data?.plan ? (
                <PlanView plan={plan.data.plan} />
              ) : (
                <p className="p-4 text-sm text-fg-muted">{t("editor.planUnavailable")}</p>
              ),
            },
            {
              id: "text",
              label: t("editor.text"),
              content: plan?.data ? (
                <pre className="p-4 font-mono text-xs leading-relaxed text-fg">{plan.data.text}</pre>
              ) : (
                <p className="p-4 text-sm text-fg-muted">{t("editor.planUnavailable")}</p>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
