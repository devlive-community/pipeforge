/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { TEXT_CONTRAST_PAIRS, THEME_MODES, THEME_STORAGE_KEY, themeInitScript } from "../src/index.js";

const tokensCss = readFileSync(new URL("../src/tokens.css", import.meta.url), "utf8");
const themeCss = readFileSync(new URL("../src/theme.css", import.meta.url), "utf8");

/** Returns the declarations of the first top-level block whose selector is exactly `selector`. */
function block(css, selector) {
  const start = css.search(new RegExp(`^${selector.replace(".", "\\.")}\\s*\\{`, "m"));
  assert.notEqual(start, -1, `selector ${selector} not found`);
  const open = css.indexOf("{", start);
  const close = css.indexOf("}", open);
  const body = css.slice(open + 1, close);
  const declarations = new Map();
  for (const match of body.matchAll(/--pf-([a-z0-9-]+)\s*:\s*([^;]+);/g)) {
    assert.ok(!declarations.has(match[1]), `--pf-${match[1]} is declared twice in ${selector}`);
    declarations.set(match[1], match[2].trim());
  }
  return declarations;
}

const light = block(tokensCss, ":root");
const dark = block(tokensCss, ".dark");

function hexToRgb(value) {
  const hex = value.replace("#", "");
  assert.match(hex, /^[0-9a-f]{6}$/i, `expected a #rrggbb color, got ${value}`);
  return [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
}

function luminance(value) {
  const [r, g, b] = hexToRgb(value).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

test("both themes define exactly the same tokens", () => {
  const onlyLight = [...light.keys()].filter((name) => !dark.has(name));
  const onlyDark = [...dark.keys()].filter((name) => !light.has(name));
  assert.deepEqual(onlyLight, [], "tokens missing in the dark theme");
  assert.deepEqual(onlyDark, [], "tokens missing in the light theme");
});

test("every token has a non-empty value", () => {
  for (const [theme, values] of [
    ["light", light],
    ["dark", dark],
  ]) {
    for (const [name, value] of values) {
      assert.ok(value.length > 0, `--pf-${name} is empty in the ${theme} theme`);
    }
  }
});

test("the documented token groups are complete", () => {
  const required = [
    "surface", "surface-2", "surface-3", "overlay", "canvas",
    "fg", "fg-muted", "fg-subtle", "fg-inverse",
    "border", "border-strong", "ring",
    "primary", "primary-hover", "primary-active", "primary-fg", "primary-soft",
    ...["success", "warning", "danger", "info"].flatMap((s) => [s, `${s}-soft`, `${s}-fg`]),
    ...["running", "success", "failed", "paused", "queued"].map((s) => `state-${s}`),
    ...Array.from({ length: 8 }, (_, i) => `chart-${i + 1}`),
    "radius-control", "radius-popover", "radius-card",
    "shadow-card", "shadow-popover", "shadow-modal",
    "control-sm", "control-md", "control-lg",
    "duration-fast", "duration-normal", "duration-slow", "ease-out", "ease-in-out",
  ];
  for (const name of required) {
    assert.ok(light.has(name), `--pf-${name} is not defined`);
  }
});

test("control sizes, radii and durations follow the specification", () => {
  assert.equal(light.get("control-sm"), "28px");
  assert.equal(light.get("control-md"), "32px");
  assert.equal(light.get("control-lg"), "36px");
  assert.equal(light.get("duration-fast"), "120ms");
  assert.equal(light.get("duration-normal"), "200ms");
  assert.equal(light.get("duration-slow"), "400ms");
  assert.equal(light.get("ease-out"), "cubic-bezier(0.16, 1, 0.3, 1)");
  assert.equal(light.get("ease-in-out"), "cubic-bezier(0.65, 0, 0.35, 1)");
  const px = (v) => Number.parseInt(v, 10);
  assert.ok(px(light.get("radius-control")) < px(light.get("radius-popover")));
  assert.ok(px(light.get("radius-popover")) < px(light.get("radius-card")));
});

test("body text reaches a contrast ratio of 4.5:1 in both themes", () => {
  const failures = [];
  for (const [theme, values] of [
    ["light", light],
    ["dark", dark],
  ]) {
    for (const [fg, bg] of TEXT_CONTRAST_PAIRS) {
      const ratio = contrast(values.get(fg), values.get(bg));
      if (ratio < 4.5) {
        failures.push(`${theme}: ${fg} on ${bg} = ${ratio.toFixed(2)}:1`);
      }
    }
  }
  assert.deepEqual(failures, []);
});

test("the contrast check itself is correct", () => {
  assert.equal(contrast("#000000", "#ffffff").toFixed(1), "21.0");
  assert.equal(contrast("#777777", "#ffffff").toFixed(2), "4.48");
  assert.equal(contrast("#ffffff", "#ffffff"), 1);
});

test("theme.css maps every token and only defined tokens", () => {
  const referenced = new Set([...themeCss.matchAll(/var\(--pf-([a-z0-9-]+)\)/g)].map((m) => m[1]));
  const undefinedRefs = [...referenced].filter((name) => !light.has(name));
  assert.deepEqual(undefinedRefs, [], "theme.css references undefined tokens");
  const unmapped = [...light.keys()].filter((name) => !referenced.has(name));
  assert.deepEqual(unmapped, [], "tokens without a Tailwind mapping");
});

test("theme.css provides the semantic utilities and animations", () => {
  for (const name of [
    "--color-surface:", "--color-fg-muted:", "--color-border:", "--color-primary:",
    "--radius-control:", "--shadow-popover:", "--spacing-control-md:",
    "--animate-fade-up:", "--animate-fade-in:", "--animate-scale-in:", "--animate-flow:",
    "--animate-pulse-soft:", "@utility duration-fast", "@utility duration-normal",
    "@custom-variant dark",
  ]) {
    assert.ok(themeCss.includes(name), `theme.css lacks ${name}`);
  }
});

test("reduced motion disables durations and decorative animations", () => {
  assert.match(tokensCss, /prefers-reduced-motion: reduce[\s\S]*--pf-duration-fast: 0ms/);
  assert.match(themeCss, /prefers-reduced-motion: reduce[\s\S]*animation: none/);
});

test("the first-frame theme script applies the stored or system theme", () => {
  assert.deepEqual(THEME_MODES, ["light", "dark", "system"]);
  const run = (stored, systemDark) => {
    const classes = new Set();
    const root = {
      dataset: {},
      classList: { toggle: (c, on) => (on ? classes.add(c) : classes.delete(c)) },
    };
    const sandbox = {
      localStorage: { getItem: (key) => (key === THEME_STORAGE_KEY ? stored : null) },
      window: { matchMedia: () => ({ matches: systemDark }) },
      document: { documentElement: root },
    };
    new Function("localStorage", "window", "document", themeInitScript)(
      sandbox.localStorage,
      sandbox.window,
      sandbox.document,
    );
    return { dark: classes.has("dark"), mode: root.dataset.themeMode };
  };
  assert.deepEqual(run("dark", false), { dark: true, mode: "dark" });
  assert.deepEqual(run("light", true), { dark: false, mode: "light" });
  assert.deepEqual(run(null, true), { dark: true, mode: "system" });
  assert.deepEqual(run("bogus", false), { dark: false, mode: "system" });
});
