/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { Link } from "react-router";
import { MappingEditor } from "@/features/field-mapping/MappingEditor";
import { api } from "@/api/client";
import { PreferenceMenus } from "@/app/PreferenceMenus";
import { setNodeOption } from "@/features/field-mapping/yamlEdit";
import { useJobSchema } from "@/api/queries";
import { useT } from "@/i18n";
import { CodeEditor } from "@/ui/CodeEditor";
import { usePreferences } from "@/state/preferences";
import {
  Badge,
  Button,
  cx,
  DagCanvas,
  FileButton,
  Icon,
  IconButton,
  SegmentedControl,
  LoadingLogo,
  LoadingPanel,
  Tabs,
  type CodeEditorHandle,
  type DagNode,
  type EditorCompletion,
} from "@/ui";
import { suggest, yamlContext } from "./completion";
import { EditorLibrary } from "./EditorLibrary";
import { addComponent, canConnect, COMPONENT_DRAG } from "./graphEdit";
import { ResizeHandle } from "./ResizeHandle";
import { EXAMPLE_JOB } from "./example";
import { PlanView } from "./PlanView";
import { OptionsPanel } from "./OptionsPanel";
import { DRY_RUN, PreviewPanel, type PreviewState } from "./PreviewPanel";
import { ProblemList } from "./ProblemList";
import { diagnosticsOf, problemsOf } from "./problems";
import { readSchema } from "./schema";
import { KINDS, readStructure, type NodeKind } from "./structure";
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
  const [view, setView] = useState("graph");
  const [compactPanel, setCompactPanel] = useState<"library" | "inspector" | null>(null);
  const libraryButton = useRef<HTMLButtonElement>(null);
  const inspectorButton = useRef<HTMLButtonElement>(null);
  const workbench = useRef<HTMLDivElement>(null);
  const center = useRef<HTMLElement>(null);
  const [leftWidth, setLeftWidth] = useState(18);
  const [rightWidth, setRightWidth] = useState(22);
  const [dockHeight, setDockHeight] = useState<number>();
  const [graphIssue, setGraphIssue] = useState<string>();
  const [focusedLine, setFocusedLine] = useState<{ number: number } | null>(null);
  const [previewNode, setPreviewNode] = useState(DRY_RUN);
  const [optionsNode, setOptionsNode] = useState<string | null>(null);
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
  const selectedNode = structure.find((node) => node.id === optionsNode) ?? structure[0];
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
      kindLabel: kind,
      selected: node.id === selectedNode?.id,
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
    setCompactPanel(null);
    setView("yaml");
    setFocusedLine({ number: line });
  };

  function openResult(id: string) {
    setCompactPanel(null);
    setTab(id);
    setResultsOpen(true);
  }

  const previewTarget = structure.some((node) => node.id === previewNode && node.kind !== "sink")
    ? previewNode
    : DRY_RUN;
  const canPreview = !checking && definition.trim() !== "" && Boolean(validation.data?.valid);
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

  function selectNode(id: string) {
    const selected = structure.find((node) => node.id === id);
    if (!selected) return;
    setCompactPanel("inspector");
    setOptionsNode(id);
    setPreviewNode(selected.kind === "sink" ? DRY_RUN : id);
    if (selected.kind === "transform" && selected.type === "field-mapper") setChosenMapper(id);
  }

  function insertComponent(kind: NodeKind, type: string) {
    if (!info?.types[kind].includes(type)) return;
    const added = addComponent(definition, kind, type);
    if (!added) {
      setGraphIssue(t("editor.invalidYaml"));
      return;
    }
    setDefinition(added.definition);
    setCompactPanel("inspector");
    setOptionsNode(added.id);
    setPreviewNode(kind === "sink" ? DRY_RUN : added.id);
    if (kind === "transform" && type === "field-mapper") setChosenMapper(added.id);
    setGraphIssue(undefined);
    setView("graph");
    return added.id;
  }

  function connectNodes(from: string, to: string) {
    const consumer = structure.find((node) => node.id === to);
    if (!consumer) return;
    const next = setNodeOption(definition, to, "inputs", consumer.inputsImplied ? [from] : [...consumer.inputs, from]);
    if (next === definition) {
      setGraphIssue(t("editor.invalidYaml"));
      return;
    }
    setDefinition(next);
    selectNode(to);
    setGraphIssue(undefined);
  }

  function removeInput(from: string) {
    if (!selectedNode || selectedNode.inputs.length < 2) return;
    const next = setNodeOption(
      definition,
      selectedNode.id,
      "inputs",
      selectedNode.inputs.filter((id) => id !== from),
    );
    if (next === definition) {
      setGraphIssue(t("editor.invalidYaml"));
      return;
    }
    setDefinition(next);
    setGraphIssue(undefined);
  }

  const tallResults = activeTab === "mapping" || activeTab === "preview";
  const resultHeight = dockHeight ?? (tallResults ? 60 : 32);
  return (
    <div className="editor-workspace">
      <header className="editor-heading">
        <div className="editor-identity">
          <Link to="/" className="editor-home" aria-label={t("notFound.back")}>
            <img src="/favicon.svg" width={26} height={26} alt="" />
          </Link>
          <div>
            <h1>
              <span className="sr-only">{t("editor.title")} · </span>
              {name}
            </h1>
            <p className="editor-file-label">{t("editor.title")} · YAML</p>
          </div>
        </div>
        <div className="workspace-page-actions">
          <Button icon="undo" variant="ghost" onClick={() => setDefinition(EXAMPLE_JOB)}>
            {t("editor.reset")}
          </Button>
          <FileButton accept=".yaml,.yml,.json" onFile={(_, text) => setDefinition(text)}>
            {t("editor.import")}
          </FileButton>
          <Button icon="download" onClick={() => download(`${name}.yaml`, definition)}>
            {t("editor.export")}
          </Button>
          <Button variant="primary" icon="play" disabled={!canPreview} onClick={() => void runPreview(DRY_RUN)}>
            {t("editor.preview.dryRun")}
          </Button>
          <PreferenceMenus />
        </div>
      </header>
      <div
        className="editor-grid"
        ref={workbench}
        onKeyDown={(event) => {
          if (event.key !== "Escape" || event.defaultPrevented || !compactPanel) return;
          const button = compactPanel === "library" ? libraryButton.current : inspectorButton.current;
          if (!button?.getClientRects().length) return;
          setCompactPanel(null);
          button.focus();
        }}
        style={{ "--editor-left": `${leftWidth}%`, "--editor-right": `${rightWidth}%` } as CSSProperties}
      >
        <EditorLibrary
          catalog={info?.types}
          loading={schema.isPending}
          nodes={structure}
          selected={selectedNode?.id}
          onAdd={insertComponent}
          onSelect={selectNode}
          compactOpen={compactPanel === "library"}
        />
        <ResizeHandle
          label={t("editor.resizeLeft")}
          direction="right"
          container={workbench}
          value={leftWidth}
          minimum={14}
          maximum={28}
          onResize={setLeftWidth}
          onReset={() => setLeftWidth(18)}
        />
        <section className="editor-center" aria-label={t("editor.view")} ref={center}>
          <div className="editor-view-picker">
            <SegmentedControl
              label={t("editor.view")}
              value={view}
              onChange={setView}
              segments={[
                { id: "graph", label: t("editor.graph") },
                { id: "yaml", label: "YAML" },
              ]}
            />
            <div className="editor-toolbar-end">
              {status}
              <div className="editor-panel-toggles">
                <IconButton
                  icon="plug"
                  size="sm"
                  label={t("editor.library")}
                  ref={libraryButton}
                  aria-controls="editor-component-library"
                  aria-expanded={compactPanel === "library"}
                  onClick={() => setCompactPanel((panel) => (panel === "library" ? null : "library"))}
                />
                <IconButton
                  icon="transform"
                  size="sm"
                  label={t("editor.inspector")}
                  ref={inspectorButton}
                  aria-controls="editor-node-configuration"
                  aria-expanded={compactPanel === "inspector"}
                  onClick={() => setCompactPanel((panel) => (panel === "inspector" ? null : "inspector"))}
                />
              </div>
            </div>
          </div>
          {graphIssue ? (
            <p role="status" className="editor-graph-issue">
              {graphIssue}
            </p>
          ) : null}
          <div hidden={view !== "yaml"} className="editor-view">
            <CodeEditor
              ref={editor}
              value={definition}
              onChange={setDefinition}
              label={t("editor.editorLabel")}
              diagnostics={diagnostics}
              completion={completion}
            />
          </div>
          <div hidden={view !== "graph"} className="editor-view">
            <DagCanvas
              className="editor-canvas"
              editor
              nodes={nodes}
              label={t("editor.graphLabel")}
              labels={{ zoomIn: t("editor.zoomIn"), zoomOut: t("editor.zoomOut"), fit: t("editor.fit") }}
              onSelect={selectNode}
              empty={t("editor.graphEmpty")}
              editing={{
                componentDragType: COMPONENT_DRAG,
                onComponent: (data) => {
                  try {
                    const [kind, type]: [NodeKind, string] = JSON.parse(data);
                    if (KINDS.includes(kind) && typeof type === "string") return insertComponent(kind, type);
                  } catch {
                    return;
                  }
                },
                canConnect: (from, to) => canConnect(structure, from, to),
                onConnect: connectNodes,
                outputLabel: (id) => t("editor.connectFrom", { id }),
                inputLabel: (id) => t("editor.connectTo", { id }),
              }}
            />
          </div>
          <ResizeHandle
            label={t("editor.resizeResults")}
            direction="up"
            container={center}
            value={resultsOpen ? resultHeight : 14}
            minimum={14}
            maximum={68}
            onResize={(value) => {
              setDockHeight(value);
              setResultsOpen(true);
            }}
            onReset={() => setDockHeight(undefined)}
          />
          <Tabs
            className={cx("editor-results border-t border-border", resultsOpen && "editor-results-open")}
            style={{ "--editor-results-size": `${resultHeight}%` } as CSSProperties}
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
                    node={previewTarget}
                    onNode={setPreviewNode}
                    onRun={() => void runPreview(previewTarget)}
                    state={preview?.node === previewTarget ? preview : null}
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
        </section>
        <ResizeHandle
          label={t("editor.resizeRight")}
          direction="left"
          container={workbench}
          value={rightWidth}
          minimum={17}
          maximum={35}
          onResize={setRightWidth}
          onReset={() => setRightWidth(22)}
        />
        <aside
          className="editor-inspector"
          id="editor-node-configuration"
          data-compact-open={compactPanel === "inspector"}
          aria-label={t("editor.inspector")}
        >
          <h2 className="editor-pane-title">
            <Icon name="transform" size={15} />
            {t("editor.inspector")}
          </h2>
          <div className="min-h-0 flex-1">
            <OptionsPanel
              structure={structure}
              definition={definition}
              node={selectedNode?.id ?? null}
              plugin={lastPlan?.nodes?.find((node) => node.id === selectedNode?.id)?.plugin}
              actions={
                selectedNode ? (
                  <>
                    <div className="editor-node-actions">
                      <Button variant="ghost" size="sm" icon="code" onClick={() => goTo(selectedNode.line)}>
                        {t("editor.openYaml")}
                      </Button>
                      {selectedNode.kind !== "sink" ? (
                        <Button
                          variant="secondary"
                          size="sm"
                          disabled={!canPreview}
                          onClick={() => void runPreview(selectedNode.id)}
                        >
                          {t("editor.preview.nodeAction")}
                        </Button>
                      ) : null}
                      {selectedNode.kind === "transform" && selectedNode.type === "field-mapper" ? (
                        <Button variant="secondary" size="sm" onClick={() => openResult("mapping")}>
                          {t("mapping.tab")}
                        </Button>
                      ) : null}
                    </div>
                    {selectedNode.inputs.length > 0 ? (
                      <section className="editor-inputs">
                        <h3>{t("editor.upstreamInputs")}</h3>
                        <ul>
                          {selectedNode.inputs.map((id, index) => (
                            <li key={`${id}-${index}`}>
                              <code>{id}</code>
                              {selectedNode.inputs.length > 1 ? (
                                <IconButton
                                  icon="minus"
                                  size="sm"
                                  label={t("editor.removeInput", { id })}
                                  onClick={() => removeInput(id)}
                                />
                              ) : null}
                            </li>
                          ))}
                        </ul>
                      </section>
                    ) : null}
                  </>
                ) : null
              }
              onNode={selectNode}
              onChange={setDefinition}
            />
          </div>
        </aside>
      </div>
    </div>
  );
}
