/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactElement, type Ref } from "react";
import { cx } from "./cx";
import { Icon, type IconName } from "./Icon";

export interface MenuItem {
  id: string;
  label: string;
  icon?: IconName;
}

/** Props the trigger element must accept; spread them onto a button. */
export interface MenuTriggerProps {
  ref: Ref<HTMLButtonElement>;
  onClick: () => void;
  onKeyDown: (e: KeyboardEvent<HTMLButtonElement>) => void;
  "aria-haspopup": "menu";
  "aria-expanded": boolean;
  "aria-controls": string | undefined;
}

/**
 * A menu opened from a trigger. With `value` the items are radio items and the chosen one is
 * checked. Keyboard: Enter, Space or the arrow keys open it; the arrow keys, Home and End move;
 * Enter or Space chooses; Escape and Tab close it. Focus returns to the trigger after closing.
 */
export function DropdownMenu({
  label,
  items,
  value,
  onSelect,
  trigger,
  align = "end",
}: {
  label: string;
  items: MenuItem[];
  value?: string;
  onSelect: (id: string) => void;
  trigger: (props: MenuTriggerProps) => ReactElement;
  align?: "start" | "end";
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const show = (index: number) => {
    setActive(index);
    setOpen(true);
  };

  const close = (returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) triggerRef.current?.focus();
  };

  useEffect(() => {
    if (open) itemRefs.current[active]?.focus();
  }, [open, active]);

  useEffect(() => {
    if (!open) return;
    const onPointer = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onPointer);
    return () => document.removeEventListener("mousedown", onPointer);
  }, [open]);

  const chosen = Math.max(
    0,
    items.findIndex((item) => item.id === value),
  );

  const onTriggerKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      show(chosen);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      show(items.length - 1);
    }
  };

  const onMenuKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const last = items.length - 1;
    const moves: Record<string, number> = {
      ArrowDown: active === last ? 0 : active + 1,
      ArrowUp: active === 0 ? last : active - 1,
      Home: 0,
      End: last,
    };
    if (e.key in moves) {
      e.preventDefault();
      setActive(moves[e.key] as number);
    } else if (e.key === "Escape") {
      e.preventDefault();
      close(true);
    } else if (e.key === "Tab") {
      close(false);
    }
  };

  return (
    <div ref={containerRef} className="relative inline-flex">
      {trigger({
        ref: triggerRef,
        onClick: () => (open ? close(false) : show(chosen)),
        onKeyDown: onTriggerKey,
        "aria-haspopup": "menu",
        "aria-expanded": open,
        "aria-controls": open ? id : undefined,
      })}
      {open ? (
        <div
          id={id}
          role="menu"
          aria-label={label}
          onKeyDown={onMenuKey}
          className={cx(
            "absolute top-full z-50 mt-1 min-w-40 rounded-popover border border-border bg-surface p-1",
            "shadow-popover animate-scale-in",
            align === "end" ? "right-0" : "left-0",
          )}
        >
          {items.map((item, index) => {
            const checked = value === undefined ? undefined : item.id === value;
            return (
              <button
                key={item.id}
                ref={(element) => {
                  itemRefs.current[index] = element;
                }}
                type="button"
                role={checked === undefined ? "menuitem" : "menuitemradio"}
                aria-checked={checked}
                tabIndex={index === active ? 0 : -1}
                onClick={() => {
                  onSelect(item.id);
                  close(true);
                }}
                onMouseEnter={() => setActive(index)}
                className={cx(
                  "flex h-control-md w-full items-center gap-2 whitespace-nowrap rounded-control px-2 text-left text-sm",
                  "text-fg transition-colors duration-fast ease-out hover:bg-surface-2 focus-visible:bg-surface-2",
                )}
              >
                {item.icon ? <Icon name={item.icon} className="text-fg-muted" /> : null}
                <span className="flex-1">{item.label}</span>
                {checked ? <Icon name="check" className="text-primary" /> : null}
              </button>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}
