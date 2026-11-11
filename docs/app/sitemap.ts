/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { MetadataRoute } from "next";
import { allPages } from "@/lib/content";
import { CURRENT_VERSION, LANGUAGES, pageUrl } from "@/lib/content-core.mjs";
import { BASE_PATH, SITE_URL } from "@/lib/site";

export const dynamic = "force-static";

export default function sitemap(): MetadataRoute.Sitemap {
  const origin = SITE_URL.replace(new RegExp(`${BASE_PATH}$`), "") + BASE_PATH;
  const homes = LANGUAGES.map((lang) => ({ url: `${origin}/${lang}/` }));
  const pages = allPages().map((page) => ({ url: origin + pageUrl(page.lang, CURRENT_VERSION, page.id) }));
  return [...homes, ...pages];
}
