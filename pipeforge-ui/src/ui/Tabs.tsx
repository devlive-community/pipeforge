/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { cx } from "./cx";

export interface TabItem {
  id: string;
  label: ReactNode;
  /** A count or marker shown after the label. */
  badge?: ReactNode;
  content: ReactNode;
}

/**
 * Tabs with a tab list and one panel. Keyboard: the arrow keys, Home and End move between tabs and
 * select them; only the selected tab is in the tab order. Controlled with `value` and `onChange`,
 * or uncontrolled starting at the first tab.
 */
export function Tabs({
  tabs,
  label,
  value,
  onChange,
  className,
}: {
  tabs: TabItem[];
  label: string;
  value?: string;
  onChange?: (id: string) => void;
  className?: string;
}) {
  const id = useId();
  const [own, setOwn] = useState(tabs[0]?.id ?? "");
  const selected = value ?? own;
  const refs = useRef<(HTMLButtonElement | null)[]>([]);

  const select = (index: number) => {
    const tab = tabs[index];
    if (!tab) return;
    setOwn(tab.id);
    onChange?.(tab.id);
    refs.current[index]?.focus();
  };

  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const current = tabs.findIndex((t) => t.id === selected);
    const last = tabs.length - 1;
    const next: Record<string, number> = {
      ArrowRight: current === last ? 0 : current + 1,
      ArrowLeft: current <= 0 ? last : current - 1,
      Home: 0,
      End: last,
    };
    if (e.key in next) {
      e.preventDefault();
      select(next[e.key] as number);
    }
  };

  const panel = tabs.find((t) => t.id === selected) ?? tabs[0];
  return (
    <div className={cx("flex min-h-0 flex-col", className)}>
      <div
        role="tablist"
        aria-label={label}
        onKeyDown={onKey}
        className="flex shrink-0 gap-1 border-b border-border px-2"
      >
        {tabs.map((tab, index) => {
          const active = tab.id === panel?.id;
          return (
            <button
              key={tab.id}
              ref={(element) => {
                refs.current[index] = element;
              }}
              type="button"
              role="tab"
              id={`${id}-tab-${tab.id}`}
              aria-selected={active}
              aria-controls={`${id}-panel`}
              tabIndex={active ? 0 : -1}
              onClick={() => select(index)}
              className={cx(
                "-mb-px flex h-control-md items-center gap-1.5 whitespace-nowrap border-b-2 px-2.5 text-sm",
                "transition-colors duration-fast ease-out",
                active ? "border-primary font-medium text-fg" : "border-transparent text-fg-muted hover:text-fg",
              )}
            >
              {tab.label}
              {tab.badge}
            </button>
          );
        })}
      </div>
      <div
        role="tabpanel"
        id={`${id}-panel`}
        aria-labelledby={panel ? `${id}-tab-${panel.id}` : undefined}
        tabIndex={0}
        className="min-h-0 flex-1 overflow-auto"
      >
        {panel?.content}
      </div>
    </div>
  );
}
