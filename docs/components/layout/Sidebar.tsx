/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { SiteLink } from "@/components/layout/SiteLink";
import { useLayoutEffect, useRef, useState } from "react";
import type { NavSection } from "@/lib/content";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { Icon } from "../ui/Icon";

/**
 * Section navigation. Groups expand and collapse with a grid-template-rows transition and
 * the indicator bar slides to the current page.
 */
export function Sidebar({
  sections,
  currentId,
  labels,
  onNavigate,
  navLabel = "Documentation",
}: {
  sections: NavSection[];
  currentId?: string;
  labels: Record<string, string>;
  onNavigate?: () => void;
  navLabel?: string;
}) {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [bar, setBar] = useState<{ top: number; height: number } | null>(null);
  const navRef = useRef<HTMLElement>(null);

  useLayoutEffect(() => {
    const nav = navRef.current;
    const active = nav?.querySelector<HTMLElement>('[aria-current="page"]');
    if (!nav || !active || active.offsetParent === null) {
      setBar(null);
      return;
    }
    const navRect = nav.getBoundingClientRect();
    const rect = active.getBoundingClientRect();
    setBar({ top: rect.top - navRect.top + nav.scrollTop, height: rect.height });
  }, [currentId, collapsed]);

  return (
    <nav ref={navRef} aria-label={navLabel} className="docs-sidebar-nav">
      {bar ? (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute z-10 left-0 w-0.5 rounded-full bg-primary transition-transform duration-normal ease-out"
          style={{ height: bar.height, transform: `translateY(${bar.top}px)` }}
        />
      ) : null}
      <ul className="space-y-3">
        {sections.map((section) => {
          const open = !collapsed[section.key];
          return (
            <li key={section.key}>
              <Button
                variant="ghost"
                size="sm"
                justify="between"
                aria-expanded={open}
                onClick={() => setCollapsed((c) => ({ ...c, [section.key]: open }))}
                className="docs-section-toggle"
              >
                {labels[section.key] ?? section.key}
                <Icon
                  name="chevron"
                  size={14}
                  className={cx("transition-transform duration-normal ease-out", open && "rotate-90")}
                />
              </Button>
              <div
                className={cx(
                  "grid transition-[grid-template-rows] duration-normal ease-out",
                  open ? "grid-rows-[1fr]" : "grid-rows-[0fr]",
                )}
              >
                <ul className="overflow-hidden" inert={!open}>
                  {section.items.map((item) => {
                    const current = item.id === currentId;
                    return (
                      <li key={item.id}>
                        <SiteLink
                          href={item.url}
                          onClick={onNavigate}
                          aria-current={current ? "page" : undefined}
                          className="docs-sidebar-item"
                        >
                          {item.title}
                        </SiteLink>
                      </li>
                    );
                  })}
                </ul>
              </div>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
