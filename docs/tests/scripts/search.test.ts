/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { parseMeta } from "@/lib/highlight";
import { rehypeHeadingIds, remarkCodeMeta } from "@/lib/mdx-plugins.mjs";
import { buildSearch, excerpt, tokenize } from "@/lib/search";
import { buildSearchDocuments, plainBody } from "@/scripts/search-index.mjs";
import { page } from "./fixtures";

describe("search index", () => {
  it("indexes plain text without code, tags or Markdown", () => {
    expect(plainBody("## Title\n\n<Callout>Use **this**</Callout>\n\n```bash\nsecret\n```\n[link](./x)")).toBe(
      "Title Use this link",
    );
  });

  it("builds one document per page of a language, in navigation order", () => {
    const pages = [
      page("en-US", "development/b", { title: "B", order: 2 }, "## H\nbody"),
      page("en-US", "getting-started/a", { title: "A", order: 1 }, "text"),
      page("zh-CN", "getting-started/a", { title: "甲", order: 1 }, "text"),
    ];
    const docs = buildSearchDocuments(pages, "en-US");
    expect(docs.map((d: { title: string }) => d.title)).toEqual(["A", "B"]);
    expect(docs[1]).toMatchObject({ url: "/en-US/next/development/b/", headings: "H", section: "development" });
  });
});

describe("client search", () => {
  const docs = [
    { id: 0, title: "Overview", url: "/o/", section: "s", headings: "", text: "connectors and plugins" },
    { id: 1, title: "Plugins", url: "/p/", section: "s", headings: "Isolation", text: "class loaders" },
    { id: 2, title: "本地开发环境", url: "/l/", section: "s", headings: "", text: "一条命令拉起数据库" },
  ];

  it("splits Han characters and words", () => {
    expect(tokenize("Run 本地 MySQL-8")).toEqual(["run", "本", "地", "mysql", "8"]);
  });

  it("ranks title matches above body matches", () => {
    const search = buildSearch(docs);
    expect(search("plugins").map((r) => r.title)).toEqual(["Plugins", "Overview"]);
    expect(search("数据库")[0]?.title).toBe("本地开发环境");
    expect(search("   ")).toEqual([]);
  });

  it("cuts an excerpt around the first match", () => {
    expect(excerpt("a".repeat(100) + "needle" + "b".repeat(100), "needle", 10)).toBe("…aaaaaaaaaaneedlebbbb…");
  });
});

describe("code blocks and headings", () => {
  it("parses titles, highlighted lines and line numbers from the fence meta", () => {
    const meta = parseMeta('title="job.yaml" {1,3-4} showLineNumbers');
    expect(meta.title).toBe("job.yaml");
    expect([...meta.highlight]).toEqual([1, 3, 4]);
    expect(meta.lineNumbers).toBe(true);
    expect(parseMeta(undefined)).toMatchObject({ title: undefined, lineNumbers: false });
  });

  it("keeps the fence meta and assigns heading ids like extractHeadings", () => {
    const mdast = { type: "root", children: [{ type: "code", meta: "{2}", value: "x" }] };
    remarkCodeMeta()(mdast as never);
    expect((mdast.children[0] as { data?: { hProperties?: object } }).data?.hProperties).toEqual({
      "data-meta": "{2}",
    });

    const heading = (text: string) => ({
      type: "element",
      tagName: "h2",
      properties: {},
      children: [{ type: "text", value: text }],
    });
    const hast = { type: "root", children: [heading("Quick start"), heading("Quick start"), heading("快速上手")] };
    rehypeHeadingIds()(hast as never);
    expect(hast.children.map((h) => (h.properties as { id: string }).id)).toEqual([
      "quick-start",
      "quick-start-1",
      "快速上手",
    ]);
  });
});
