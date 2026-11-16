/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useEffect } from "react";
import { NavLink, Outlet, useLocation, useNavigation } from "react-router";
import { useT } from "@/i18n";
import { applyTheme, usePreferences } from "@/state/preferences";
import { cx, Icon } from "@/ui";
import { LoadingPanel } from "@/ui/LoadingPanel";
import { NAVIGATION } from "./navigation";
import { PreferenceMenus } from "./PreferenceMenus";

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
  const { pathname } = useLocation();
  const navigation = useNavigation();
  const current = NAVIGATION.find((item) => item.to === (pathname.replace(/\/$/, "") || "/"));
  const changingPage = Boolean(navigation.location && navigation.location.pathname !== pathname);
  useDocumentPreferences();
  return (
    <div className="workspace">
      <a
        href="#content"
        className="sr-only z-50 rounded-control bg-primary px-3 py-2 text-primary-fg focus:not-sr-only focus:fixed focus:left-3 focus:top-3"
      >
        {t("app.skipToContent")}
      </a>
      <header className="workspace-header">
        <div className="workspace-brand">
          <span className="workspace-mark">
            <img src="/favicon.svg" width={27} height={27} alt="" />
          </span>
          {t("app.name")}
        </div>
        {current ? (
          <span className="workspace-location">
            <Icon name={current.icon} size={15} />
            {t(current.label)}
          </span>
        ) : null}
        <div className="ml-auto">
          <PreferenceMenus />
        </div>
      </header>
      <nav aria-label={t("app.mainNavigation")} className="workspace-nav">
        {NAVIGATION.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end
            className={({ isActive }) => cx("workspace-link", isActive && "workspace-link-active")}
          >
            <Icon name={item.icon} size={17} />
            <span>{t(item.label)}</span>
          </NavLink>
        ))}
      </nav>
      <main id="content" tabIndex={-1} className="workspace-main">
        <Outlet />
      </main>
      {changingPage ? <LoadingPanel className="workspace-pending" /> : null}
    </div>
  );
}
