/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Small unified plugins for the MDX pipeline. Plain JavaScript so that tests and scripts can
// use them without a build step.

import { uniqueSlug } from "./content-core.mjs";

/** Keeps the code fence meta string (title="...", {1-3}) as a data-meta attribute. */
export function remarkCodeMeta() {
  return (tree) => {
    visit(tree, (node) => {
      if (node.type === "code" && node.meta) {
        node.data ??= {};
        node.data.hProperties = { ...(node.data.hProperties ?? {}), "data-meta": node.meta };
      }
    });
  };
}

/** Gives h2 and h3 the same unique ids that extractHeadings computes from the Markdown. */
export function rehypeHeadingIds() {
  return (tree) => {
    const seen = new Map();
    visit(tree, (node) => {
      if (node.type === "element" && (node.tagName === "h2" || node.tagName === "h3")) {
        node.properties ??= {};
        node.properties.id = uniqueSlug(textOf(node), seen);
      }
    });
  };
}

function textOf(node) {
  if (node.type === "text") return node.value;
  return (node.children ?? []).map(textOf).join("");
}

function visit(node, fn) {
  fn(node);
  for (const child of node.children ?? []) visit(child, fn);
}
