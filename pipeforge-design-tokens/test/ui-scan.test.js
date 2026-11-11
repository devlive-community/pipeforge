/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { scanSource, scanUi } from "../src/ui-scan.js";

const rules = (path, text, dirs) => scanSource(path, text, dirs).map((v) => v.rule);

test("native controls and CodeMirror are allowed only in the component library", () => {
  const ui = ["src/ui/"];
  assert.deepEqual(rules("src/features/a.tsx", "<button onClick={f}>x</button>", ui), ["native-control"]);
  assert.deepEqual(rules("src/ui/Button.tsx", "<button type={type} />", ui), []);
  assert.deepEqual(rules("src/features/a.tsx", 'import { EditorView } from "@codemirror/view";', ui), [
    "codemirror-import",
  ]);
  assert.deepEqual(rules("src/ui/CodeEditor.tsx", 'import { EditorView } from "@codemirror/view";', ui), []);
  assert.deepEqual(rules("components/ui/Button.tsx", "<button />"), []);
});

test("colors must come from semantic tokens", () => {
  assert.deepEqual(rules("src/a.tsx", '<div className="bg-gray-100 dark:text-fg" />'), ["raw-palette", "dark-variant"]);
  assert.deepEqual(rules("src/a.tsx", '<div className="bg-surface text-fg-muted" />'), []);
  assert.deepEqual(rules("src/a.css", "  color: #fff;"), ["css-hex-color"]);
});

test("scans a tree and reports path, line and rule", () => {
  const root = mkdtempSync(join(tmpdir(), "ui-scan-"));
  mkdirSync(join(root, "src", "ui"), { recursive: true });
  writeFileSync(join(root, "src", "Page.tsx"), "const a = 1;\n<select />\n");
  writeFileSync(join(root, "src", "ui", "Select.tsx"), "<select />\n");
  assert.deepEqual(scanUi(root, ["src", "missing"], ["src/ui/"]), [
    "src/Page.tsx:2: native-control: use the components of the component library instead of native controls",
  ]);
});
