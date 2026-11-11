/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useEffect } from "react";
import { NavLink, Outlet } from "react-router";
import { useT } from "@/i18n";
import { applyTheme, usePreferences } from "@/state/preferences";
import { cx, Icon } from "@/ui";
import { NAVIGATION } from "./navigation";
import { PreferenceMenus } from "./PreferenceMenus";

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

/** Keeps the document in step with the preferences: theme class, system theme changes, lang. */
function useDocumentPreferences() {
  const theme = usePreferences((s) => s.theme);
  const language = usePreferences((s) => s.language);
  useEffect(() => {
    applyTheme(theme);
    if (theme !== "system" || typeof window.matchMedia !== "function") return;
    const query = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => applyTheme("system");
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, [theme]);
  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);
}

/** The frame of every page: top bar, main navigation and the page itself. */
export function Shell() {
  const t = useT();
  useDocumentPreferences();
  return (
    <div className="flex min-h-dvh flex-col bg-surface text-fg">
      <a
        href="#content"
        className="sr-only z-50 rounded-control bg-primary px-3 py-2 text-primary-fg focus:not-sr-only focus:absolute focus:left-3 focus:top-3"
      >
        {t("app.skipToContent")}
      </a>
      <header className="flex h-14 shrink-0 items-center gap-3 border-b border-border bg-surface px-4">
        <span className="flex items-center gap-2 font-semibold text-fg">
          <Logo />
          {t("app.name")}
        </span>
        <div className="ml-auto">
          <PreferenceMenus />
        </div>
      </header>
      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        <nav
          aria-label={t("app.mainNavigation")}
          className="flex shrink-0 gap-1 overflow-x-auto border-b border-border bg-surface-2 p-2 md:w-56 md:flex-col md:border-b-0 md:border-r"
        >
          {NAVIGATION.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end
              className={({ isActive }) =>
                cx(
                  "flex h-control-md items-center gap-2 whitespace-nowrap rounded-control px-2.5 text-sm",
                  "transition-colors duration-fast ease-out",
                  isActive ? "bg-primary-soft font-medium text-fg" : "text-fg-muted hover:bg-surface-3 hover:text-fg",
                )
              }
            >
              <Icon name={item.icon} />
              {t(item.label)}
            </NavLink>
          ))}
        </nav>
        <main id="content" tabIndex={-1} className="min-w-0 flex-1 p-6 outline-none">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
