/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { themeInitScript } from "@pipeforge/design-tokens";
import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";
import type { ReactNode } from "react";
import "@/styles/index.css";
import { LANGUAGES } from "@/lib/content-core.mjs";
import { t, toLang } from "@/lib/i18n";
import { BRAND_MARK, SITE_URL } from "@/lib/site";

const geist = localFont({
  src: "../../styles/fonts/geist-latin.woff2",
  variable: "--site-font-geist",
  weight: "100 900",
  display: "swap",
});

export const dynamicParams = false;

export function generateStaticParams() {
  return LANGUAGES.map((lang) => ({ lang }));
}

export async function generateMetadata({ params }: { params: Promise<{ lang: string }> }): Promise<Metadata> {
  const dict = t(toLang((await params).lang));
  return {
    metadataBase: new URL(SITE_URL),
    icons: { icon: { url: BRAND_MARK, type: "image/svg+xml" } },
    title: { default: dict.siteName, template: `%s · ${dict.siteName}` },
    description: dict.tagline,
  };
}

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "white" },
    { media: "(prefers-color-scheme: dark)", color: "black" },
  ],
};

export default async function LangLayout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ lang: string }>;
}) {
  const lang = toLang((await params).lang);
  return (
    <html lang={lang} className={geist.variable} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeInitScript }} />
      </head>
      <body className="bg-surface text-fg">
        <a
          href="#content"
          className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-control focus:bg-surface focus:px-3 focus:py-2 focus:shadow-popover"
        >
          {t(lang).skipToContent}
        </a>
        {children}
      </body>
    </html>
  );
}
