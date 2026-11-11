/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { existsSync } from "node:fs";
import { join } from "node:path";
import type { Lang } from "@/lib/i18n";
import { BASE_PATH } from "@/lib/site";

/** Where `pnpm --filter @pipeforge/ui screenshots` writes the Web UI screenshots. */
export const SCREENSHOT_DIR = join("public", "screenshots", "ui");

/** The file of a screenshot in one theme and language. */
export function screenshotFile(name: string, theme: "light" | "dark", lang: Lang) {
  return `${name}-${theme}-${lang}.png`;
}

/**
 * A Web UI screenshot in the language of the page; the light or dark version follows the site
 * theme. The build fails when a screenshot is missing, so pages never show broken images.
 */
export function Screenshot({ name, alt, lang = "en-US" }: { name: string; alt: string; lang?: Lang }) {
  for (const theme of ["light", "dark"] as const) {
    const file = join(process.cwd(), SCREENSHOT_DIR, screenshotFile(name, theme, lang));
    if (!existsSync(file)) {
      throw new Error(`Screenshot ${file} is missing; run pnpm --filter @pipeforge/ui screenshots`);
    }
  }
  const src = (theme: "light" | "dark") => `${BASE_PATH}/screenshots/ui/${screenshotFile(name, theme, lang)}`;
  return (
    <figure className="not-prose my-6">
      <img
        src={src("light")}
        alt={alt}
        width={1280}
        height={800}
        loading="lazy"
        className="screenshot-light h-auto w-full rounded-card border border-border shadow-card"
      />
      <img
        src={src("dark")}
        alt={alt}
        width={1280}
        height={800}
        loading="lazy"
        className="screenshot-dark h-auto w-full rounded-card border border-border shadow-card"
      />
    </figure>
  );
}
