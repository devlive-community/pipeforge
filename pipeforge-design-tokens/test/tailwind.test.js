/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { compile } from "@tailwindcss/node";

const srcDir = fileURLToPath(new URL("../src/", import.meta.url));

async function build(candidates) {
  const input = '@import "tailwindcss";\n@import "./tokens.css";\n@import "./theme.css";\n';
  const compiler = await compile(input, { base: srcDir, onDependency: () => {} });
  return compiler.build(candidates);
}

test("semantic utilities compile to token variables", async () => {
  const css = await build([
    "bg-surface",
    "text-fg-muted",
    "border-border",
    "bg-primary",
    "text-primary-fg",
    "bg-state-running",
    "fill-chart-3",
    "rounded-control",
    "rounded-card",
    "shadow-popover",
    "h-control-md",
    "duration-fast",
    "ease-out",
    "animate-fade-up",
    "animate-flow",
  ]);
  // @theme inline resolves every utility straight to the token variable.
  const expectations = {
    ".bg-surface": "var(--pf-surface)",
    ".text-fg-muted": "var(--pf-fg-muted)",
    ".border-border": "var(--pf-border)",
    ".bg-primary": "var(--pf-primary)",
    ".text-primary-fg": "var(--pf-primary-fg)",
    ".bg-state-running": "var(--pf-state-running)",
    ".fill-chart-3": "var(--pf-chart-3)",
    ".rounded-control": "var(--pf-radius-control)",
    ".rounded-card": "var(--pf-radius-card)",
    ".shadow-popover": "var(--pf-shadow-popover)",
    ".h-control-md": "var(--pf-control-md)",
    ".duration-fast": "var(--pf-duration-fast)",
    ".ease-out": "var(--pf-ease-out)",
    ".animate-fade-up": "pf-fade-up var(--pf-duration-slow) var(--pf-ease-out)",
    ".animate-flow": "pf-flow",
  };
  for (const [selector, value] of Object.entries(expectations)) {
    assert.ok(css.includes(`${selector} {`), `${selector} was not generated`);
    const rule = css.slice(css.indexOf(`${selector} {`));
    assert.ok(rule.slice(0, rule.indexOf("}")).includes(value), `${selector} does not use ${value}`);
  }
  assert.match(css, /@keyframes pf-fade-up/);
});

test("the dark variant follows the .dark class", async () => {
  const css = await build(["dark:bg-surface"]);
  assert.match(css, /:where\(\.dark, \.dark \*\)/);
});

test("raw palette utilities still exist but are not part of the tokens", async () => {
  // The UI scan rejects raw palette classes in application code; this documents that the
  // token mapping does not hide Tailwind's default palette from that scan.
  const css = await build(["bg-gray-100"]);
  assert.match(css, /\.bg-gray-100/);
});
