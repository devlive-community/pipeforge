/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import FlexSearch from "flexsearch";

export interface SearchDocument {
  id: number;
  title: string;
  url: string;
  section: string;
  headings: string;
  text: string;
}

export interface SearchResult {
  title: string;
  url: string;
  excerpt: string;
}

/**
 * Splits text into search terms: words of alphabetic scripts and digits, and every single
 * Han character, so Chinese text matches without a dictionary.
 */
export function tokenize(text: string): string[] {
  const terms: string[] = [];
  for (const match of text.toLowerCase().matchAll(/\p{Script=Han}|[\p{L}\p{N}]+/gu)) terms.push(match[0]);
  return terms;
}

/** A short piece of `text` around the first term of the query. */
export function excerpt(text: string, query: string, radius = 60): string {
  const first = tokenize(query)[0];
  const index = first ? text.toLowerCase().indexOf(first) : -1;
  if (index < 0) return text.slice(0, radius * 2);
  const start = Math.max(0, index - radius);
  return (start > 0 ? "…" : "") + text.slice(start, index + radius).trim() + (index + radius < text.length ? "…" : "");
}

/** Builds a search function over the documents; titles rank above headings and body text. */
export function buildSearch(documents: SearchDocument[], limit = 12): (query: string) => SearchResult[] {
  const index = new FlexSearch.Document({
    document: { id: "id", index: ["title", "headings", "text"] },
    tokenize: "forward",
    encoder: { encode: tokenize } as never,
  });
  for (const doc of documents) index.add(doc as never);
  const byId = new Map(documents.map((doc) => [doc.id, doc]));

  return (query: string) => {
    if (query.trim() === "") return [];
    const ranked = new Map<number, number>();
    const weights: Record<string, number> = { title: 3, headings: 2, text: 1 };
    for (const field of index.search(query, { limit: limit * 2 }) as unknown as Array<{
      field: string;
      result: number[];
    }>) {
      field.result.forEach((id, position) => {
        const score = (weights[field.field] ?? 1) * (1 / (position + 1));
        ranked.set(id, (ranked.get(id) ?? 0) + score);
      });
    }
    return [...ranked.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, limit)
      .map(([id]) => byId.get(id))
      .filter((doc): doc is SearchDocument => doc !== undefined)
      .map((doc) => ({ title: doc.title, url: doc.url, excerpt: excerpt(doc.text, query) }));
  };
}
