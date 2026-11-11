/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { useEffect, useState } from "react";
import type { NavSection } from "@/lib/content";
import { IconButton } from "../ui/IconButton";
import { Sidebar } from "./Sidebar";

/** Navigation drawer for narrow screens. */
export function MobileNav({
  sections,
  currentId,
  labels,
  menuLabel,
}: {
  sections: NavSection[];
  currentId?: string;
  labels: Record<string, string>;
  menuLabel: string;
}) {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => event.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <div className="lg:hidden">
      <IconButton
        icon={open ? "close" : "menu"}
        label={menuLabel}
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      />
      {open ? (
        <div className="fixed inset-x-0 top-14 bottom-0 z-40 overflow-y-auto border-t border-border bg-surface p-4 animate-fade-in">
          <Sidebar sections={sections} currentId={currentId} labels={labels} onNavigate={() => setOpen(false)} />
        </div>
      ) : null}
    </div>
  );
}
