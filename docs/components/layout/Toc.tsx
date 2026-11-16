/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { useEffect, useState } from "react";
import type { Heading } from "@/lib/content";
import { cx } from "../ui/cx";
import { Icon } from "../ui/Icon";

/** "On this page": highlights the heading being read. */
export function Toc({ headings, title, compact }: { headings: Heading[]; title: string; compact?: boolean }) {
  const [active, setActive] = useState(headings[0]?.id);

  useEffect(() => {
    const elements = headings.map((h) => document.getElementById(h.id)).filter((el): el is HTMLElement => el !== null);
    if (elements.length === 0 || typeof IntersectionObserver === "undefined") return;
    const visible = new Set<string>();
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) visible.add(entry.target.id);
          else visible.delete(entry.target.id);
        }
        const first = elements.find((el) => visible.has(el.id));
        if (first) setActive(first.id);
      },
      { rootMargin: "-72px 0px -60% 0px" },
    );
    elements.forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [headings]);

  if (headings.length === 0) return null;
  return (
    <nav aria-label={title} className="docs-toc">
      {compact ? null : (
        <p className="docs-toc-title">
          <Icon name="list" size={16} />
          {title}
        </p>
      )}
      <ul className="border-l border-border">
        {headings.map((heading) => (
          <li key={heading.id}>
            <a
              href={`#${heading.id}`}
              aria-current={heading.id === active ? "location" : undefined}
              className={cx(
                "block py-1 text-sm transition-colors duration-fast",
                heading.depth === 3 ? "pl-6" : "pl-3",
                heading.id === active ? "text-primary" : "text-fg-muted hover:text-fg",
              )}
            >
              {heading.text}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
