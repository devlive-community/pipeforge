/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { useEffect, useRef, useState } from "react";
import { cx } from "../ui/cx";

/** Feature cards that fade in and move up one after another when they scroll into view. */
export function FeatureGrid({ features }: { features: ReadonlyArray<readonly [string, string]> }) {
  const [visible, setVisible] = useState(false);
  const grid = useRef<HTMLUListElement>(null);

  useEffect(() => {
    const element = grid.current;
    if (!element) return;
    if (typeof IntersectionObserver === "undefined") {
      setVisible(true);
      return;
    }
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) {
          setVisible(true);
          observer.disconnect();
        }
      },
      { rootMargin: "0px 0px -10% 0px" },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return (
    <ul ref={grid} className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {features.map(([title, text], index) => (
        <li
          key={title}
          className={cx(
            "rounded-card border border-border bg-surface p-5 shadow-card",
            visible ? "animate-fade-up" : "opacity-0",
          )}
          style={visible ? { animationDelay: `${index * 60}ms` } : undefined}
        >
          <h3 className="font-semibold text-fg">{title}</h3>
          <p className="mt-2 text-sm leading-6 text-fg-muted">{text}</p>
        </li>
      ))}
    </ul>
  );
}
