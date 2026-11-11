/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { themeInitScript } from "@pipeforge/design-tokens";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import "@/styles/index.css";
import { SITE_URL } from "@/lib/site";

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: "PipeForge",
  description: "PipeForge documentation: pluggable distributed data integration.",
};

export default function RedirectLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeInitScript }} />
      </head>
      <body className="bg-surface text-fg">{children}</body>
    </html>
  );
}
