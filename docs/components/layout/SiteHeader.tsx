/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { SiteLink } from "@/components/layout/SiteLink";
import type { NavSection } from "@/lib/content";
import { CURRENT_VERSION } from "@/lib/content-core.mjs";
import { t, type Lang } from "@/lib/i18n";
import { integrationLabels } from "@/lib/integration";
import { cx } from "../ui/cx";
import { Icon } from "../ui/Icon";
import { MobileNav } from "./MobileNav";
import { SiteBrand } from "./SiteBrand";
import { Search } from "./Search";
import { ThemeToggle } from "./ThemeToggle";

export const REPOSITORY_URL = "https://github.com/devlive-community/pipeforge";

export function SiteHeader({
  lang,
  otherLangHref,
  sections,
  currentId,
  area,
  version = CURRENT_VERSION,
}: {
  lang: Lang;
  otherLangHref: string;
  sections: NavSection[];
  currentId?: string;
  area: "home" | "docs" | "docs-index";
  version?: string;
}) {
  const dict = t(lang);
  const labels = integrationLabels(lang);
  const home = area === "home";
  const docsHref = `/${lang}/${version}/`;
  return (
    <header className="site-header">
      <div className={cx("site-header-inner", area === "docs" ? "docs-container" : "site-container")}>
        <MobileNav
          sections={sections}
          currentId={currentId}
          labels={dict.sections}
          menuLabel={dict.menu}
          docsHref={docsHref}
          docsLabel={dict.docs}
        />
        <SiteBrand lang={lang} />
        <nav className="site-primary-nav" aria-label={lang === "zh-CN" ? "主导航" : "Main navigation"}>
          <SiteLink href={`/${lang}/`} className="site-docs-link" aria-current={home ? "page" : undefined}>
            {labels.overview}
          </SiteLink>
          <SiteLink
            href={docsHref}
            className="site-docs-link"
            aria-current={area === "docs-index" ? "page" : !home ? "location" : undefined}
          >
            {dict.docs}
          </SiteLink>
          <SiteLink href={`${docsHref}#connectors`} className="site-docs-link">
            {labels.connectors}
          </SiteLink>
        </nav>
        <div className="site-header-tools">
          {area !== "docs-index" && <Search key={lang} lang={lang} labels={dict} />}
          <SiteLink
            href={otherLangHref}
            hrefLang={lang === "en-US" ? "zh-CN" : "en-US"}
            aria-label={dict.languageLabel}
            className="site-icon-link"
          >
            <Icon name="globe" size={17} />
            <span className="hidden md:inline">{dict.language}</span>
          </SiteLink>
          <ThemeToggle dict={dict} />
          <a
            href={REPOSITORY_URL}
            target="_blank"
            rel="noreferrer"
            aria-label={dict.viewSource}
            className="site-icon-link site-source-link"
          >
            <Icon name="github" size={18} />
            <span className="hidden lg:inline">GitHub</span>
          </a>
        </div>
      </div>
    </header>
  );
}
