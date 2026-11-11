/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import {
  countCodeBlocks,
  extractHeadings,
  extractLinks,
  pageUrl,
  plainText,
  resolveLink,
  slugify,
  sortPages,
} from "@/lib/content-core.mjs";
import { page } from "./fixtures";

describe("slugify", () => {
  it("keeps letters and digits of every script", () => {
    expect(slugify("Quick start")).toBe("quick-start");
    expect(slugify("`explain` output (JSON)")).toBe("explain-output-json");
    expect(slugify("快速上手")).toBe("快速上手");
    expect(slugify("行为 与 语义")).toBe("行为-与-语义");
  });
});

describe("extractHeadings", () => {
  it("collects level 2 and 3 headings outside code with unique ids", () => {
    const body = "## Intro\n### Details\n```md\n## Not a heading\n```\n## Intro\n#### Too deep\n## **Bold** [link](x)";
    expect(extractHeadings(body)).toEqual([
      { depth: 2, text: "Intro", id: "intro" },
      { depth: 3, text: "Details", id: "details" },
      { depth: 2, text: "Intro", id: "intro-1" },
      { depth: 2, text: "Bold link", id: "bold-link" },
    ]);
  });

  it("strips inline Markdown", () => {
    expect(plainText("Use `run --local` with *care*")).toBe("Use run --local with care");
  });
});

describe("links and code blocks", () => {
  it("finds Markdown and href links outside code", () => {
    const body = 'See [a](./a) and [b](../b#x).\n`[c](./c)`\n```\n[d](./d)\n```\n<a href="./e">e</a>\n![img](./i.png)';
    expect(extractLinks(body).map((l) => l.href)).toEqual(["./a", "../b#x", "./e"]);
  });

  it("counts fenced code blocks, including tilde fences", () => {
    expect(countCodeBlocks("```a\nx\n```\ntext\n~~~\ny\n~~~\n")).toBe(2);
  });
});

describe("resolveLink", () => {
  it("resolves relative page links and anchors", () => {
    expect(resolveLink("development/docs-guide", "./local-environment")).toEqual({
      kind: "page",
      id: "development/local-environment",
      anchor: "",
    });
    expect(resolveLink("development/docs-guide", "../concepts/plugins#isolation")).toEqual({
      kind: "page",
      id: "concepts/plugins",
      anchor: "isolation",
    });
    expect(resolveLink("development/docs-guide", "#checks")).toEqual({
      kind: "page",
      id: "development/docs-guide",
      anchor: "checks",
    });
    expect(resolveLink("a/b", "./c.mdx")).toMatchObject({ id: "a/c" });
  });

  it("recognizes external links and rejects absolute or escaping ones", () => {
    expect(resolveLink("a/b", "https://example.org")).toMatchObject({ kind: "external" });
    expect(resolveLink("a/b", "mailto:x@example.org")).toMatchObject({ kind: "external" });
    expect(resolveLink("a/b", "/en-US/next/a/b/")).toMatchObject({ kind: "invalid" });
    expect(resolveLink("a/b", "../../../x")).toMatchObject({ kind: "invalid" });
  });

  it("builds page URLs with language and version", () => {
    expect(pageUrl("zh-CN", "next", "concepts/plugins")).toBe("/zh-CN/next/concepts/plugins/");
  });
});

describe("sortPages", () => {
  it("orders by section, then order, then id", () => {
    const pages = [
      page("en-US", "development/b", { title: "b", order: 2 }, "x"),
      page("en-US", "getting-started/z", { title: "z", order: 50 }, "x"),
      page("en-US", "development/a", { title: "a", order: 2 }, "x"),
      page("en-US", "development/c", { title: "c", order: 1 }, "x"),
    ];
    expect(sortPages(pages).map((p) => p.id)).toEqual([
      "getting-started/z",
      "development/c",
      "development/a",
      "development/b",
    ]);
  });
});
