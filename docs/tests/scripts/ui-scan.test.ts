/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { scanSource } from "@/scripts/checks/ui-scan.mjs";

const rules = (path: string, text: string) => scanSource(path, text).map((v: { rule: string }) => v.rule);

describe("UI scan", () => {
  it("accepts semantic tokens", () => {
    expect(
      rules("components/a.tsx", '<div className="bg-surface text-fg-muted border-border hover:bg-primary-hover" />'),
    ).toEqual([]);
  });

  it("flags raw palette colors, also with variants and opacity", () => {
    expect(rules("components/a.tsx", '<div className="bg-gray-100" />')).toEqual(["raw-palette"]);
    expect(rules("components/a.tsx", '<div className="hover:text-blue-600/50" />')).toEqual(["raw-palette"]);
    expect(rules("components/a.tsx", '<div className="text-white" />')).toEqual(["raw-palette"]);
  });

  it("flags arbitrary colors, dark variants and inline colors", () => {
    expect(rules("components/a.tsx", '<div className="bg-[#1e293b]" />')).toEqual(["arbitrary-color"]);
    expect(rules("components/a.tsx", '<div className="dark:bg-surface" />')).toEqual(["dark-variant"]);
    expect(rules("components/a.tsx", "<div style={{ color: 'red' }} />")).toEqual(["inline-color"]);
  });

  it("flags native controls outside components/ui only", () => {
    expect(rules("components/layout/a.tsx", "<button onClick={f}>x</button>")).toEqual(["native-control"]);
    expect(rules("app/page.tsx", '<input type="checkbox" />')).toEqual(["native-control"]);
    expect(rules("components/ui/Button.tsx", "<button type={type} />")).toEqual([]);
  });

  it("flags title tooltips on elements but not component props", () => {
    expect(rules("components/a.tsx", '<span title="hint">x</span>')).toEqual(["title-tooltip"]);
    expect(rules("components/a.tsx", "<Toc title={dict.onThisPage} />")).toEqual([]);
  });

  it("flags browser dialogs and hex colors in CSS", () => {
    expect(rules("components/a.tsx", 'window.confirm("sure?")')).toEqual(["browser-dialog"]);
    expect(rules("styles/index.css", "  color: #fff;")).toEqual(["css-hex-color"]);
    expect(rules("styles/index.css", "  color: var(--pf-fg);")).toEqual([]);
  });

  it("ignores comments and explicitly ignored lines", () => {
    expect(rules("components/a.tsx", "// bg-gray-100 in a comment")).toEqual([]);
    expect(rules("components/a.tsx", '<div className="bg-gray-100" /> {/* ui-scan-ignore */}')).toEqual([]);
  });
});
