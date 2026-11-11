/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ErrorInfo, ValidationResult } from "@pipeforge/client";
import type { EditorDiagnostic } from "@/ui";
import type { StructureNode } from "./structure";

/** A problem of the job, placed on a line when its location is known. */
export interface Problem {
  severity: "error" | "warning";
  message: string;
  code?: string;
  hint?: string;
  line?: number;
  column?: number;
}

function placeError(error: ErrorInfo, nodes: StructureNode[]): Problem {
  const scope = (error.scope ?? {}) as Record<string, unknown>;
  const problem: Problem = { severity: "error", message: error.message ?? "", code: error.code, hint: error.hint };
  if (typeof scope.line === "number") {
    problem.line = scope.line;
    problem.column = typeof scope.column === "number" ? scope.column : undefined;
  } else if (typeof scope.node === "string") {
    problem.line = nodes.find((n) => n.id === scope.node)?.line;
  }
  return problem;
}

/** The problems of a validation: errors at their location in the definition or at their node, then warnings. */
export function problemsOf(result: ValidationResult | undefined, nodes: StructureNode[]): Problem[] {
  if (!result) return [];
  const errors = (result.errors ?? []).map((e) => placeError(e, nodes));
  const warnings = (result.warnings ?? []).map((message): Problem => {
    const node = /node '([^']+)'/.exec(message)?.[1];
    return { severity: "warning", message, line: nodes.find((n) => n.id === node)?.line };
  });
  return [...errors, ...warnings];
}

/** The problems with a line, as editor diagnostics. */
export function diagnosticsOf(problems: Problem[]): EditorDiagnostic[] {
  return problems
    .filter((p): p is Problem & { line: number } => p.line !== undefined)
    .map((p) => ({
      line: p.line,
      column: p.column,
      severity: p.severity,
      message: p.code ? `${p.code} ${p.message}` : p.message,
    }));
}
