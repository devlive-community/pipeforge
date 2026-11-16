/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { SiteLink } from "@/components/layout/SiteLink";
import { useEffect, useRef, useState } from "react";
import type { NavSection } from "@/lib/content";
import { IconButton } from "../ui/IconButton";
import { Sidebar } from "./Sidebar";

/** Navigation drawer for narrow screens. */
export function MobileNav({
  sections,
  currentId,
  labels,
  menuLabel,
  docsHref,
  docsLabel,
}: {
  sections: NavSection[];
  currentId?: string;
  labels: Record<string, string>;
  menuLabel: string;
  docsHref: string;
  docsLabel: string;
}) {
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const drawer = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    drawer.current?.querySelector("a")?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || document.querySelector('[role="dialog"]')) return;
      setOpen(false);
      trigger.current?.focus();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <div className="docs-mobile-nav">
      <IconButton
        ref={trigger}
        icon={open ? "close" : "menu"}
        label={menuLabel}
        aria-expanded={open}
        aria-controls={open ? "docs-drawer" : undefined}
        onClick={() => setOpen(!open)}
      />
      {open ? (
        <div ref={drawer} id="docs-drawer" className="docs-drawer">
          <SiteLink href={docsHref} className="docs-sidebar-home" onClick={() => setOpen(false)}>
            {docsLabel}
          </SiteLink>
          <Sidebar
            navLabel={docsLabel}
            sections={sections}
            currentId={currentId}
            labels={labels}
            onNavigate={() => setOpen(false)}
          />
        </div>
      ) : null}
    </div>
  );
}
