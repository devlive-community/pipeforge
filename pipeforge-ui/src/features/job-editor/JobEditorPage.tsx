/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { MappingEditor } from "@/features/field-mapping/MappingEditor";
import { api } from "@/api/client";
import { useJobSchema } from "@/api/queries";
import { useT } from "@/i18n";
import { CodeEditor } from "@/ui/CodeEditor";
import { usePreferences } from "@/state/preferences";
import {
  Badge,
  Button,
  Card,
  cx,
  DagCanvas,
  FileButton,
  Icon,
  IconButton,
  SegmentedControl,
  LoadingLogo,
  LoadingPanel,
  Splitter,
  Tabs,
  type CodeEditorHandle,
  type DagNode,
  type EditorCompletion,
} from "@/ui";
import { suggest, yamlContext } from "./completion";
import { EXAMPLE_JOB } from "./example";
import { PlanView } from "./PlanView";
import { DRY_RUN, PreviewPanel, type PreviewState } from "./PreviewPanel";
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
  const [tab, setTab] = useState("problems");
  const [resultsOpen, setResultsOpen] = useState(false);
  const [view, setView] = useState("yaml");
  const [focusedLine, setFocusedLine] = useState<{ number: number } | null>(null);
  const [previewNode, setPreviewNode] = useState(DRY_RUN);
  const [preview, setPreview] = useState<PreviewState | null>(null);
  const previewing = useRef(0);

  useLayoutEffect(() => {
    if (focusedLine) editor.current?.focusLine(focusedLine.number);
  }, [focusedLine]);

  // the mapping editor keeps showing the last valid plan while the job is being changed
  const current = plan?.data?.plan;
  const [lastPlan, setLastPlan] = useState(current);
  useEffect(() => {
    if (current) setLastPlan(current);
  }, [current]);

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

  const mappers = structure.filter((n) => n.kind === "transform" && n.type === "field-mapper");
  const [chosenMapper, setChosenMapper] = useState<string | null>(null);
  const mapper = mappers.find((n) => n.id === chosenMapper) ?? mappers[0];
  const activeTab = tab === "mapping" && !mapper ? "problems" : tab;

  const errorCount = problems.filter((p) => p.severity === "error").length;
  useEffect(() => {
    if (errorCount === 0 && !validation.isError) return;
    setResultsOpen(true);
    setTab((selected) => (selected === "mapping" ? selected : "problems"));
  }, [errorCount, validation.isError]);

  const status =
    definition.trim() === "" ? null : checking ? (
      <span role="status" className="flex items-center gap-1.5 text-sm text-fg-muted">
        <LoadingLogo size={18} />
        {t("editor.checking")}
      </span>
    ) : validation.data?.valid ? (
      <Badge tone="success">{t("editor.valid")}</Badge>
    ) : validation.data ? (
      <Badge tone="danger">{t("editor.invalid", { count: errorCount })}</Badge>
    ) : null;

  const name = /^name:\s*(\S+)/m.exec(definition)?.[1] ?? "job";
  const goTo = (line: number) => {
    setView("yaml");
    setFocusedLine({ number: line });
  };

  function openResult(id: string) {
    setTab(id);
    setResultsOpen(true);
  }

  const canPreview = definition.trim() !== "" && Boolean(validation.data?.valid);
  /** Runs a preview of `node` (or a dry run) on the server; a later preview replaces an earlier one. */
  async function runPreview(node: string) {
    const ticket = ++previewing.current;
    setPreviewNode(node);
    openResult("preview");
    setPreview({ node, definition, pending: true });
    try {
      const result = await api.previewJob({ definition, ...(node === DRY_RUN ? {} : { node }) });
      if (ticket === previewing.current) setPreview({ node, definition, result, pending: false });
    } catch (error) {
      if (ticket === previewing.current) setPreview({ node, definition, error: error as Error, pending: false });
    }
  }

  return (
    <div className="editor-workspace">
      <header className="editor-heading">
        <div className="editor-identity">
          <h1>{t("editor.title")}</h1>
          <span className="editor-filename">{name}.yaml</span>
        </div>
        <div className="workspace-page-actions">
          {status}
          <FileButton accept=".yaml,.yml,.json" onFile={(_, text) => setDefinition(text)}>
            {t("editor.import")}
          </FileButton>
          <Button icon="play" disabled={!canPreview} onClick={() => void runPreview(DRY_RUN)}>
            {t("editor.preview.dryRun")}
          </Button>
          <Button icon="download" onClick={() => download(`${name}.yaml`, definition)}>
            {t("editor.export")}
          </Button>
          <Button icon="undo" variant="ghost" onClick={() => setDefinition(EXAMPLE_JOB)}>
            {t("editor.reset")}
          </Button>
        </div>
      </header>
      <Card flush className="editor-card flex min-h-0 flex-1 flex-col overflow-hidden">
        <div className="editor-view-picker">
          <SegmentedControl
            label={t("editor.view")}
            value={view}
            onChange={setView}
            segments={[
              { id: "yaml", label: "YAML" },
              { id: "graph", label: t("editor.graph") },
            ]}
          />
        </div>
        <Splitter
          className={cx("editor-panes min-h-0 flex-1 overflow-hidden", view === "graph" && "editor-show-graph")}
          label={t("editor.splitter")}
          initial={57}
          first={
            <section className="editor-pane">
              <h2 className="editor-pane-title">
                <Icon name="code" size={15} />
                {t("editor.editorLabel")}
              </h2>
              <div className="min-h-0 flex-1">
                <CodeEditor
                  ref={editor}
                  value={definition}
                  onChange={setDefinition}
                  label={t("editor.editorLabel")}
                  diagnostics={diagnostics}
                  completion={completion}
                />
              </div>
            </section>
          }
          second={
            <section className="editor-pane">
              <h2 className="editor-pane-title">
                <Icon name="topology" size={15} />
                {t("editor.graph")}
              </h2>
              <div className="relative min-h-0 flex-1">
                {nodes.length > 0 ? (
                  <DagCanvas
                    nodes={nodes}
                    label={t("editor.graphLabel")}
                    labels={{ zoomIn: t("editor.zoomIn"), zoomOut: t("editor.zoomOut"), fit: t("editor.fit") }}
                    onSelect={(id) => {
                      const selected = structure.find((n) => n.id === id);
                      // a source or transform becomes what the preview shows; running it stays a choice
                      if (selected && selected.kind !== "sink") setPreviewNode(id);
                      if (selected?.type === "field-mapper") {
                        setChosenMapper(id);
                        openResult("mapping");
                      }
                      if (selected?.line) goTo(selected.line);
                    }}
                  />
                ) : (
                  <div className="dag-surface flex h-full min-h-48 items-center justify-center p-4 text-center text-sm text-fg-muted">
                    {t("editor.graphEmpty")}
                  </div>
                )}
              </div>
            </section>
          }
        />
        <Tabs
          className={cx(
            "editor-results border-t border-border",
            resultsOpen &&
              (activeTab === "mapping" || activeTab === "preview" ? "editor-results-mapping" : "editor-results-open"),
          )}
          collapsed={!resultsOpen}
          panelAction={(panelId) => (
            <IconButton
              className="mx-2"
              icon={resultsOpen ? "chevronDown" : "chevronUp"}
              label={t(resultsOpen ? "editor.hideResults" : "editor.showResults")}
              aria-controls={panelId}
              aria-expanded={resultsOpen}
              onClick={() => setResultsOpen((open) => !open)}
            />
          )}
          label={t("editor.panels")}
          value={activeTab}
          onChange={openResult}
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
                <LoadingPanel />
              ) : (
                <ProblemList problems={problems} onGoTo={goTo} />
              ),
            },
            {
              id: "plan",
              label: t("editor.plan"),
              content: plan?.data?.plan ? (
                <PlanView plan={plan.data.plan} />
              ) : plan?.isFetching ? (
                <LoadingPanel />
              ) : (
                <p className="p-4 text-sm text-fg-muted">{t("editor.planUnavailable")}</p>
              ),
            },
            ...(mapper
              ? [
                  {
                    id: "mapping",
                    label: t("mapping.tab"),
                    content: (
                      <div className="flex h-full min-h-0 flex-col">
                        {mappers.length > 1 ? (
                          <div className="border-b border-border px-4 py-2">
                            <SegmentedControl
                              label={t("mapping.node")}
                              value={mapper.id}
                              onChange={setChosenMapper}
                              segments={mappers.map((n) => ({ id: n.id, label: n.id }))}
                            />
                          </div>
                        ) : null}
                        <div className="min-h-0 flex-1">
                          <MappingEditor
                            source={definition}
                            nodeId={mapper.id}
                            node={lastPlan?.nodes?.find((n) => n.id === mapper.id)}
                            stale={!current}
                            onChange={setDefinition}
                          />
                        </div>
                      </div>
                    ),
                  },
                ]
              : []),
            {
              id: "preview",
              label: t("editor.preview.tab"),
              content: (
                <PreviewPanel
                  structure={structure}
                  definition={definition}
                  node={previewNode}
                  onNode={setPreviewNode}
                  onRun={() => void runPreview(previewNode)}
                  state={preview}
                  canRun={canPreview}
                />
              ),
            },
            {
              id: "text",
              label: t("editor.text"),
              content: plan?.data ? (
                <pre className="p-4 font-mono text-xs leading-relaxed text-fg">{plan.data.text}</pre>
              ) : plan?.isFetching ? (
                <LoadingPanel />
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
