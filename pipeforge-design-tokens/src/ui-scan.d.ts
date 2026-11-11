/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

export interface UiRule {
  id: string;
  message: string;
  pattern: RegExp;
  componentsOnly?: boolean;
  jsxOnly?: boolean;
  cssOnly?: boolean;
}

export interface UiViolation {
  path: string;
  line: number;
  rule: string;
  message: string;
}

export declare const RULES: UiRule[];

/** Scans one file's text; native controls and CodeMirror are allowed in `componentDirs` only. */
export declare function scanSource(path: string, text: string, componentDirs?: string[]): UiViolation[];

/** Scans `dirs` under `root`; returns "path:line: rule: message" lines. */
export declare function scanUi(root: string, dirs: string[], componentDirs?: string[]): string[];
