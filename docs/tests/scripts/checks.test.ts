/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { FEATURE_SECTIONS, PLUGIN_SECTIONS } from "@/lib/content-core.mjs";
import { checkI18n } from "@/scripts/checks/i18n.mjs";
import { checkLinks } from "@/scripts/checks/links.mjs";
import { checkStructure } from "@/scripts/checks/structure.mjs";
import { guide, page } from "./fixtures";

const featureBody = (lang: "en-US" | "zh-CN", skip: string[] = []) =>
  FEATURE_SECTIONS.filter((s) => !skip.includes(s.key))
    .map((s) => `## ${s[lang]}\n\ncontent\n`)
    .join("\n");

describe("structure check", () => {
  it("accepts a complete guide page", () => {
    expect(checkStructure([page("en-US", "development/x", guide, "## A\n\ntext")])).toEqual([]);
  });

  it("requires title and description", () => {
    const errors = checkStructure([page("en-US", "development/x", { order: 1 }, "text")]);
    expect(errors.join("\n")).toMatch(/needs a title/);
    expect(errors.join("\n")).toMatch(/needs a description/);
  });

  it("rejects unknown sections, templates, TODO markers and level 1 headings", () => {
    const errors = checkStructure([
      page("en-US", "misc/x", { ...guide, template: "essay" }, "# Title\n\nTODO: write\n\n`TODO` in code is fine"),
    ]).join("\n");
    expect(errors).toMatch(/'misc' is not a content section/);
    expect(errors).toMatch(/unknown template 'essay'/);
    expect(errors).toMatch(/remove the TODO marker/);
    expect(errors).toMatch(/level 1 heading/);
  });

  it("accepts a feature page with every section", () => {
    expect(
      checkStructure([page("zh-CN", "concepts/x", { ...guide, template: "feature" }, featureBody("zh-CN"))]),
    ).toEqual([]);
  });

  it("requires omitted sections to be named with a reason", () => {
    const missing = checkStructure([
      page("en-US", "concepts/x", { ...guide, template: "feature" }, featureBody("en-US", ["performance"])),
    ]);
    expect(missing).toEqual([expect.stringMatching(/missing section 'Performance and tuning'/)]);

    const omitted = checkStructure([
      page(
        "en-US",
        "concepts/x",
        { ...guide, template: "feature", omit: { performance: "not applicable" } },
        featureBody("en-US", ["performance"]),
      ),
    ]);
    expect(omitted).toEqual([]);

    const noReason = checkStructure([
      page(
        "en-US",
        "concepts/x",
        { ...guide, template: "feature", omit: { performance: "" } },
        featureBody("en-US", ["performance"]),
      ),
    ]);
    expect(noReason.join("\n")).toMatch(/omitting 'performance' needs a reason/);
  });

  it("rejects present-but-omitted, unknown, extra, out-of-order and empty sections", () => {
    const errors = checkStructure([
      page(
        "en-US",
        "concepts/x",
        { ...guide, template: "feature", omit: { overview: "x", bogus: "y" } },
        `## How it works\n\n## Overview\n\ntext\n\n## Extra\n\ntext\n` +
          featureBody("en-US", ["overview", "principle"]),
      ),
    ]).join("\n");
    expect(errors).toMatch(/'Overview' is both present and omitted/);
    expect(errors).toMatch(/unknown section 'bogus'/);
    expect(errors).toMatch(/'Extra' is not a section of the feature template/);
    expect(errors).toMatch(/section 'How it works' is empty/);
  });

  it("requires template order", () => {
    const [first, second, ...rest] = FEATURE_SECTIONS;
    const body = [second, first, ...rest].map((s) => `## ${s!["en-US"]}\n\ntext\n`).join("\n");
    const errors = checkStructure([page("en-US", "concepts/x", { ...guide, template: "feature" }, body)]);
    expect(errors).toEqual([expect.stringMatching(/not in template order/)]);
  });

  it("checks plugin pages against the plugin template and location", () => {
    const body = PLUGIN_SECTIONS.map((s) => `## ${s["en-US"]}\n\ntext\n`).join("\n");
    expect(checkStructure([page("en-US", "plugins/source/fake", { ...guide, template: "plugin" }, body)])).toEqual([]);
    expect(checkStructure([page("en-US", "concepts/fake", { ...guide, template: "plugin" }, body)]).join("\n")).toMatch(
      /plugin pages live under plugins/,
    );
  });
});

describe("i18n check", () => {
  it("accepts matching pages", () => {
    expect(
      checkI18n([
        page("en-US", "a/b", guide, "## One\n\n```\nx\n```"),
        page("zh-CN", "a/b", guide, "## 一\n\n```\nx\n```"),
      ]),
    ).toEqual([]);
  });

  it("reports missing translations and structural differences", () => {
    const errors = checkI18n([
      page("en-US", "a/only-en", guide, "x"),
      page(
        "en-US",
        "a/b",
        { ...guide, template: "feature", omit: { performance: "x" } },
        "## One\n### Two\n```\nx\n```",
      ),
      page("zh-CN", "a/b", { ...guide, order: 2 }, "## 一\n## 二"),
    ]).join("\n");
    expect(errors).toMatch(/zh-CN\/a\/only-en.mdx: missing translation/);
    expect(errors).toMatch(/different templates/);
    expect(errors).toMatch(/different order values/);
    expect(errors).toMatch(/omit different sections/);
    expect(errors).toMatch(/different heading structures/);
    expect(errors).toMatch(/different numbers of code blocks/);
  });
});

describe("links check", () => {
  const target = page("en-US", "concepts/plugins", guide, "## Isolation\n\ntext");

  it("accepts links to existing pages and anchors", () => {
    const source = page(
      "en-US",
      "development/x",
      guide,
      "[p](../concepts/plugins#isolation) [e](https://example.org) [s](#here)\n## Here",
    );
    expect(checkLinks([target, source])).toEqual([]);
  });

  it("reports missing pages, missing anchors and site-absolute links", () => {
    const source = page(
      "en-US",
      "development/x",
      guide,
      "[a](../concepts/missing)\n[b](../concepts/plugins#nope)\n[c](/en-US/next/concepts/plugins/)",
    );
    const errors = checkLinks([target, source]);
    expect(errors).toHaveLength(3);
    expect(errors[0]).toMatch(/x.mdx:1: .*'concepts\/missing' does not exist/);
    expect(errors[1]).toMatch(/has no heading '#nope'/);
    expect(errors[2]).toMatch(/site-absolute/);
  });

  it("accepts anchors that a page renders from generated data", () => {
    const errors = page("en-US", "reference/errors", { ...guide, data: "errors" }, "## All error codes");
    const source = page("en-US", "concepts/x", guide, "[e](../reference/errors#pf-conf-0001)");
    expect(checkLinks([errors, source])).toHaveLength(1);
    expect(checkLinks([errors, source], { errors: ["pf-conf-0001"] })).toEqual([]);
  });

  it("does not resolve links across languages", () => {
    const source = page("zh-CN", "development/x", guide, "[p](../concepts/plugins)");
    expect(checkLinks([target, source])).toHaveLength(1);
  });
});
