/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { parsePage } from "@/lib/content-core.mjs";

/** Builds a page record from frontmatter fields and a body, like loadPages does. */
export function page(lang: "en-US" | "zh-CN", id: string, frontmatter: Record<string, unknown>, body: string) {
  const yaml = Object.entries(frontmatter)
    .map(([key, value]) =>
      typeof value === "object" && value !== null
        ? `${key}:\n${Object.entries(value)
            .map(([k, v]) => `  ${k}: ${JSON.stringify(v)}`)
            .join("\n")}`
        : `${key}: ${JSON.stringify(value)}`,
    )
    .join("\n");
  return parsePage(lang, id, `---\n${yaml}\n---\n${body}`);
}

export const guide = { title: "T", description: "D", order: 1 };
