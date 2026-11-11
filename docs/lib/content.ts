/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { join } from "node:path";
import { cache } from "react";
import { CURRENT_VERSION, SECTIONS, loadPages, pageUrl, sortPages } from "./content-core.mjs";
import type { Lang } from "./i18n";

export interface Heading {
  depth: number;
  text: string;
  id: string;
}

export interface Page {
  lang: Lang;
  id: string;
  file: string;
  frontmatter: { title: string; description: string; order?: number; template?: string; omit?: Record<string, string> };
  body: string;
  headings: Heading[];
}

export interface NavItem {
  id: string;
  title: string;
  url: string;
}

export interface NavSection {
  key: string;
  items: NavItem[];
}

const CONTENT_DIR = join(process.cwd(), "content");

export const allPages = cache((): Page[] => sortPages(loadPages(CONTENT_DIR)) as Page[]);

export function pagesOf(lang: Lang): Page[] {
  return allPages().filter((page) => page.lang === lang);
}

export function findPage(lang: Lang, id: string): Page | undefined {
  return pagesOf(lang).find((page) => page.id === id);
}

export function navigation(lang: Lang): NavSection[] {
  const sections: NavSection[] = [];
  for (const key of SECTIONS) {
    const items = pagesOf(lang)
      .filter((page) => page.id.split("/")[0] === key)
      .map((page) => ({ id: page.id, title: page.frontmatter.title, url: pageUrl(lang, CURRENT_VERSION, page.id) }));
    if (items.length > 0) sections.push({ key, items });
  }
  return sections;
}

export function neighbours(lang: Lang, id: string): { previous?: NavItem; next?: NavItem } {
  const items = navigation(lang).flatMap((section) => section.items);
  const index = items.findIndex((item) => item.id === id);
  return { previous: index > 0 ? items[index - 1] : undefined, next: items[index + 1] };
}
