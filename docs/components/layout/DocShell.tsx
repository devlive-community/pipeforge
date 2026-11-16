/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { SiteLink } from "@/components/layout/SiteLink";
import type { ReactNode } from "react";
import type { NavSection } from "@/lib/content";
import { t, type Lang } from "@/lib/i18n";
import { Icon } from "../ui/Icon";
import { cx } from "../ui/cx";
import { PageTransition } from "./PageTransition";
import { Sidebar } from "./Sidebar";
import { SiteFooter } from "./SiteFooter";
import { SiteHeader } from "./SiteHeader";

export function DocShell({
  lang,
  version,
  sections,
  currentId,
  otherLangHref,
  aside,
  children,
}: {
  lang: Lang;
  version: string;
  sections: NavSection[];
  currentId?: string;
  otherLangHref: string;
  aside?: ReactNode;
  children: ReactNode;
}) {
  const dict = t(lang);
  return (
    <>
      <SiteHeader
        lang={lang}
        version={version}
        otherLangHref={otherLangHref}
        sections={sections}
        currentId={currentId}
        area="docs"
      />
      <div className={cx("docs-container docs-layout", Boolean(aside) && "docs-layout-with-toc")}>
        <aside className="docs-sidebar">
          <SiteLink
            href={`/${lang}/${version}/`}
            className="docs-sidebar-home"
            aria-current={!currentId ? "page" : undefined}
          >
            <span className="docs-sidebar-home-icon">
              <Icon name="book" />
            </span>
            <span>
              {dict.docs}
              <small>{version === "next" ? dict.docsSeries : version}</small>
            </span>
          </SiteLink>
          <Sidebar sections={sections} currentId={currentId} labels={dict.sections} navLabel={dict.docs} />
        </aside>
        <main id="content" className="docs-main">
          <PageTransition>{children}</PageTransition>
        </main>
        {aside ? <aside className="docs-outline">{aside}</aside> : null}
      </div>
      <SiteFooter lang={lang} version={version} />
    </>
  );
}
