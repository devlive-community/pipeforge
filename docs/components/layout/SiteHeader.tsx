/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import Link from "next/link";
import type { NavSection } from "@/lib/content";
import { t, type Lang } from "@/lib/i18n";
import { Icon } from "../ui/Icon";
import { MobileNav } from "./MobileNav";
import { Search } from "./Search";
import { ThemeToggle } from "./ThemeToggle";

export const REPOSITORY_URL = "https://github.com/devlive-community/pipeforge";

export function SiteHeader({
  lang,
  otherLangHref,
  sections,
  currentId,
}: {
  lang: Lang;
  otherLangHref: string;
  sections: NavSection[];
  currentId?: string;
}) {
  const dict = t(lang);
  return (
    <header className="sticky top-0 z-30 border-b border-border bg-surface/90 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-screen-2xl items-center gap-3 px-4 sm:px-6">
        <MobileNav sections={sections} currentId={currentId} labels={dict.sections} menuLabel={dict.menu} />
        <Link href={`/${lang}/`} className="flex items-center gap-2 font-semibold text-fg">
          <Logo />
          {dict.siteName}
        </Link>
        <div className="ml-auto flex items-center gap-1.5">
          <Search
            lang={lang}
            labels={{
              search: dict.search,
              searchPlaceholder: dict.searchPlaceholder,
              searchEmpty: dict.searchEmpty,
              searchHint: dict.searchHint,
            }}
          />
          <Link
            href={otherLangHref}
            hrefLang={lang === "en-US" ? "zh-CN" : "en-US"}
            aria-label={dict.languageLabel}
            className="inline-flex h-control-md items-center gap-1.5 rounded-control px-2 text-sm text-fg-muted transition-colors duration-fast hover:bg-surface-2 hover:text-fg"
          >
            <Icon name="globe" size={15} />
            <span className="hidden sm:inline">{dict.language}</span>
          </Link>
          <ThemeToggle dict={dict} />
          <a
            href={REPOSITORY_URL}
            target="_blank"
            rel="noreferrer"
            aria-label={dict.viewSource}
            className="inline-flex size-control-md items-center justify-center rounded-control text-fg-muted transition-colors duration-fast hover:bg-surface-2 hover:text-fg"
          >
            <Icon name="github" size={17} />
          </a>
        </div>
      </div>
    </header>
  );
}

function Logo() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" className="text-primary">
      <circle cx="5" cy="12" r="3" fill="currentColor" />
      <circle cx="19" cy="6" r="3" fill="currentColor" opacity="0.7" />
      <circle cx="19" cy="18" r="3" fill="currentColor" opacity="0.7" />
      <path d="M8 12h4l4-5M12 12l4 5" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" />
    </svg>
  );
}
