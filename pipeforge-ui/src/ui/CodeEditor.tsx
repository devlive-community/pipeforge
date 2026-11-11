/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { autocompletion, completionKeymap, type CompletionContext } from "@codemirror/autocomplete";
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { yaml } from "@codemirror/lang-yaml";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { lintGutter, lintKeymap, setDiagnostics, type Diagnostic } from "@codemirror/lint";
import { EditorState } from "@codemirror/state";
import { EditorView, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers } from "@codemirror/view";
import { tags } from "@lezer/highlight";
import { useEffect, useImperativeHandle, useRef, type Ref } from "react";
import { cx } from "./cx";

/** A problem to show in the editor; lines and columns start at 1. */
export interface EditorDiagnostic {
  line: number;
  column?: number;
  severity: "error" | "warning" | "info";
  message: string;
}

/** One completion offered by the caller. */
export interface EditorSuggestion {
  label: string;
  type?: "property" | "enum" | "variable" | "keyword";
  detail?: string;
  info?: string;
  /** The text inserted; the label when omitted. */
  apply?: string;
}

/**
 * Completion supplied by the caller: for the document text and the cursor position, where the
 * completed word starts and what to offer, or null. `explicit` is true when asked with Ctrl+Space.
 */
export type EditorCompletion = (
  text: string,
  position: number,
  explicit: boolean,
) => { from: number; options: EditorSuggestion[] } | null;

export interface CodeEditorHandle {
  /** Moves the cursor to the start of a line, scrolls it into view and focuses the editor. */
  focusLine: (line: number) => void;
}

// Colors come from the design tokens, so the editor follows the theme without its own theme.
const theme = EditorView.theme({
  "&": { color: "var(--pf-fg)", backgroundColor: "var(--pf-surface)", height: "100%", fontSize: "13px" },
  ".cm-content": { caretColor: "var(--pf-fg)", fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" },
  ".cm-cursor": { borderLeftColor: "var(--pf-fg)" },
  ".cm-gutters": { backgroundColor: "var(--pf-surface-2)", color: "var(--pf-fg-subtle)", border: "none" },
  ".cm-activeLine": { backgroundColor: "var(--pf-surface-2)" },
  ".cm-activeLineGutter": { backgroundColor: "var(--pf-surface-3)", color: "var(--pf-fg)" },
  "&.cm-focused": { outline: "none" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground": { backgroundColor: "var(--pf-primary-soft)" },
  ".cm-tooltip": {
    backgroundColor: "var(--pf-surface)",
    color: "var(--pf-fg)",
    border: "1px solid var(--pf-border)",
    borderRadius: "var(--pf-radius-popover)",
    boxShadow: "var(--pf-shadow-popover)",
  },
  ".cm-tooltip-autocomplete ul li[aria-selected]": { backgroundColor: "var(--pf-primary-soft)", color: "var(--pf-fg)" },
  ".cm-completionDetail": { color: "var(--pf-fg-muted)" },
  ".cm-completionInfo": { padding: "6px 8px", maxWidth: "320px" },
  ".cm-diagnostic-error": { borderLeftColor: "var(--pf-danger)" },
  ".cm-diagnostic-warning": { borderLeftColor: "var(--pf-warning)" },
  ".cm-lintRange-error": { backgroundImage: "none", textDecoration: "underline wavy var(--pf-danger)" },
  ".cm-lintRange-warning": { backgroundImage: "none", textDecoration: "underline wavy var(--pf-warning)" },
});

const highlight = HighlightStyle.define([
  { tag: [tags.propertyName, tags.definition(tags.propertyName)], color: "var(--pf-syntax-keyword)" },
  { tag: [tags.string, tags.special(tags.string)], color: "var(--pf-syntax-string)" },
  { tag: [tags.number, tags.bool, tags.null], color: "var(--pf-syntax-number)" },
  { tag: tags.comment, color: "var(--pf-syntax-comment)" },
  { tag: [tags.keyword, tags.typeName], color: "var(--pf-syntax-type)" },
]);

/** Converts diagnostics with lines and columns to document ranges: the rest of the line from the column. */
export function toRanges(text: string, diagnostics: EditorDiagnostic[]): Diagnostic[] {
  const starts = [0];
  for (let i = 0; i < text.length; i++) if (text[i] === "\n") starts.push(i + 1);
  return diagnostics.map((d) => {
    const line = Math.min(Math.max(d.line, 1), starts.length);
    const lineStart = starts[line - 1] ?? 0;
    const lineEnd = line < starts.length ? (starts[line] ?? text.length) - 1 : text.length;
    const from = Math.min(lineStart + Math.max((d.column ?? 1) - 1, 0), lineEnd);
    return { from, to: Math.max(lineEnd, from), severity: d.severity, message: d.message };
  });
}

/**
 * A YAML code editor (CodeMirror 6, the only place it is used): line numbers, history,
 * completion from `completion`, and problems shown inline and in the gutter. Tab is left to move
 * the focus on; the editor's own commands use the usual shortcuts.
 */
export function CodeEditor({
  value,
  onChange,
  label,
  diagnostics = [],
  completion,
  className,
  ref,
}: {
  value: string;
  onChange: (value: string) => void;
  label: string;
  diagnostics?: EditorDiagnostic[];
  completion?: EditorCompletion;
  className?: string;
  ref?: Ref<CodeEditorHandle>;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const latest = useRef({ onChange, completion });
  latest.current = { onChange, completion };
  // The last text the editor reported; a value prop equal to it is the editor's own echo.
  const emitted = useRef(value);

  useEffect(() => {
    const editor = new EditorView({
      parent: host.current as HTMLDivElement,
      state: EditorState.create({
        doc: value,
        extensions: [
          lineNumbers(),
          highlightActiveLineGutter(),
          highlightActiveLine(),
          history(),
          yaml(),
          syntaxHighlighting(highlight),
          autocompletion({
            override: [
              (context: CompletionContext) =>
                latest.current.completion?.(context.state.doc.toString(), context.pos, context.explicit) ?? null,
            ],
          }),
          lintGutter(),
          keymap.of([...defaultKeymap, ...historyKeymap, ...completionKeymap, ...lintKeymap]),
          theme,
          EditorView.contentAttributes.of({ "aria-label": label }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) {
              emitted.current = update.state.doc.toString();
              latest.current.onChange(emitted.current);
            }
          }),
        ],
      }),
    });
    view.current = editor;
    return () => {
      editor.destroy();
      view.current = null;
    };
    // The editor is created once; value and diagnostics are synchronized below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Only values from outside (import, reset) replace the document. While typing, React may render
  // with a value a keystroke behind the editor; writing that back would drop characters.
  useEffect(() => {
    const editor = view.current;
    if (!editor || value === emitted.current) return;
    emitted.current = value;
    if (editor.state.doc.toString() !== value) {
      editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } });
    }
  }, [value]);

  useEffect(() => {
    const editor = view.current;
    if (editor) editor.dispatch(setDiagnostics(editor.state, toRanges(editor.state.doc.toString(), diagnostics)));
  }, [diagnostics, value]);

  useImperativeHandle(ref, () => ({
    focusLine: (line: number) => {
      const editor = view.current;
      if (!editor) return;
      const target = editor.state.doc.line(Math.min(Math.max(line, 1), editor.state.doc.lines));
      editor.dispatch({ selection: { anchor: target.from }, scrollIntoView: true });
      editor.focus();
    },
  }));

  return <div ref={host} className={cx("h-full min-h-0 overflow-hidden", className)} data-testid="code-editor" />;
}
