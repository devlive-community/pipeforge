/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";
import type { NavSection } from "@/lib/content";
import { t, type Lang } from "@/lib/i18n";
import { PageTransition } from "./PageTransition";
import { Sidebar } from "./Sidebar";
import { SiteFooter } from "./SiteFooter";
import { SiteHeader } from "./SiteHeader";

/** Header, sidebar navigation, main content and an optional right column. */
export function DocShell({
  lang,
  sections,
  currentId,
  otherLangHref,
  aside,
  children,
}: {
  lang: Lang;
  sections: NavSection[];
  currentId?: string;
  otherLangHref: string;
  aside?: ReactNode;
  children: ReactNode;
}) {
  const dict = t(lang);
  return (
    <>
      <SiteHeader lang={lang} otherLangHref={otherLangHref} sections={sections} currentId={currentId} />
      <div className="mx-auto flex max-w-screen-2xl gap-10 px-4 sm:px-6">
        <aside className="sticky top-14 hidden h-[calc(100dvh-3.5rem)] w-64 shrink-0 overflow-y-auto py-8 lg:block">
          <Sidebar sections={sections} currentId={currentId} labels={dict.sections} />
        </aside>
        <main id="content" className="min-w-0 flex-1 py-10">
          <PageTransition>{children}</PageTransition>
        </main>
        {aside ? (
          <aside className="sticky top-14 hidden h-[calc(100dvh-3.5rem)] w-56 shrink-0 overflow-y-auto py-10 xl:block">
            {aside}
          </aside>
        ) : null}
      </div>
      <SiteFooter lang={lang} />
    </>
  );
}
