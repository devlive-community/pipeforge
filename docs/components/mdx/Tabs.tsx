/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import {
  Children,
  isValidElement,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import { TabButton } from "../ui/TabButton";

interface TabProps {
  label: string;
  children: ReactNode;
}

/** One panel of <Tabs>; rendered by its parent. */
export function Tab({ children }: TabProps) {
  return <>{children}</>;
}

/**
 * Switchable panels (YAML / SQL / CLI, database versions). The underline slides to the
 * selected tab and the panel fades in; arrow keys, Home and End move between tabs.
 */
export function Tabs({ children, defaultIndex = 0 }: { children: ReactNode; defaultIndex?: number }) {
  const tabs = Children.toArray(children).filter((c): c is ReactElement<TabProps> => isValidElement(c));
  const [selected, setSelected] = useState(Math.min(defaultIndex, Math.max(tabs.length - 1, 0)));
  const [indicator, setIndicator] = useState({ left: 0, width: 0 });
  const buttons = useRef<Array<HTMLButtonElement | null>>([]);
  const id = useId();

  useLayoutEffect(() => {
    const button = buttons.current[selected];
    if (button) setIndicator({ left: button.offsetLeft, width: button.offsetWidth });
  }, [selected, tabs.length]);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const last = tabs.length - 1;
    const next =
      event.key === "ArrowRight"
        ? (selected + 1) % tabs.length
        : event.key === "ArrowLeft"
          ? (selected - 1 + tabs.length) % tabs.length
          : event.key === "Home"
            ? 0
            : event.key === "End"
              ? last
              : null;
    if (next === null) return;
    event.preventDefault();
    setSelected(next);
    buttons.current[next]?.focus();
  }

  return (
    <div className="rounded-popover border border-border">
      <div
        role="tablist"
        onKeyDown={onKeyDown}
        className="relative flex gap-1 overflow-x-auto border-b border-border px-2"
      >
        {tabs.map((tab, index) => (
          <TabButton
            key={index}
            ref={(el) => {
              buttons.current[index] = el;
            }}
            selected={index === selected}
            id={`${id}-tab-${index}`}
            aria-controls={`${id}-panel-${index}`}
            onClick={() => setSelected(index)}
          >
            {tab.props.label}
          </TabButton>
        ))}
        <span
          aria-hidden="true"
          className="absolute bottom-0 left-0 h-0.5 bg-primary transition-transform duration-normal ease-out"
          style={{ width: indicator.width, transform: `translateX(${indicator.left}px)` }}
        />
      </div>
      {tabs.map((tab, index) => (
        <div
          key={index}
          role="tabpanel"
          id={`${id}-panel-${index}`}
          aria-labelledby={`${id}-tab-${index}`}
          hidden={index !== selected}
          className="animate-fade-in p-4 [&>*+*]:mt-3"
        >
          {tab.props.children}
        </div>
      ))}
    </div>
  );
}
