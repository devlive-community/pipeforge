/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { LANGUAGES } from "./content-core.mjs";

/** Public URL of the site (used for canonical links, hreflang and the sitemap). */
export const SITE_URL = (process.env.DOCS_SITE_URL ?? "https://devlive-community.github.io/pipeforge").replace(
  /\/$/,
  "",
);

export const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

/** hreflang alternates for a path that exists in every language, e.g. "/next/concepts/x/". */
export function alternates(lang: string, pathAfterLang: string) {
  return {
    canonical: `/${lang}${pathAfterLang}`,
    languages: Object.fromEntries(LANGUAGES.map((l) => [l, `/${l}${pathAfterLang}`])),
  };
}
