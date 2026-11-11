/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { Heading } from "@/lib/content";
import { cx } from "../ui/cx";

/** "On this page": highlights the heading being read; the indicator follows smoothly. */
export function Toc({ headings, title }: { headings: Heading[]; title: string }) {
  const [active, setActive] = useState(headings[0]?.id);
  const [bar, setBar] = useState<{ top: number; height: number } | null>(null);
  const listRef = useRef<HTMLUListElement>(null);

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

  useLayoutEffect(() => {
    const link = listRef.current?.querySelector<HTMLElement>(`[data-id="${CSS.escape(active ?? "")}"]`);
    setBar(link ? { top: link.offsetTop, height: link.offsetHeight } : null);
  }, [active]);

  if (headings.length === 0) return null;
  return (
    <nav aria-label={title}>
      <p className="mb-3 text-sm font-semibold text-fg">{title}</p>
      <ul ref={listRef} className="relative border-l border-border">
        {bar ? (
          <span
            aria-hidden="true"
            className="absolute -left-px w-0.5 bg-primary transition-transform duration-normal ease-out"
            style={{ height: bar.height, transform: `translateY(${bar.top}px)` }}
          />
        ) : null}
        {headings.map((heading) => (
          <li key={heading.id}>
            <a
              href={`#${heading.id}`}
              data-id={heading.id}
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
