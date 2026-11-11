/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { cloneElement, useEffect, useId, useRef, useState, type ReactElement } from "react";
import { cx } from "./cx";

const DELAY_MS = 300;

/**
 * A text hint for its child, shown on hover after a short delay and at once on keyboard focus;
 * Escape hides it. With `describe` the child is described by the hint (aria-describedby); leave it
 * off when the hint repeats the child's accessible name.
 */
export function Tooltip({
  content,
  children,
  placement = "bottom",
  describe = true,
}: {
  content: string;
  children: ReactElement<{ "aria-describedby"?: string }>;
  placement?: "top" | "bottom";
  describe?: boolean;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const hide = () => {
    clearTimeout(timer.current);
    setOpen(false);
  };

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  useEffect(() => () => clearTimeout(timer.current), []);

  return (
    <span
      className="relative inline-flex"
      onMouseEnter={() => {
        clearTimeout(timer.current);
        timer.current = setTimeout(() => setOpen(true), DELAY_MS);
      }}
      onMouseLeave={hide}
      onFocus={() => setOpen(true)}
      onBlur={hide}
    >
      {cloneElement(children, { "aria-describedby": describe && open ? id : undefined })}
      {open ? (
        <span
          role="tooltip"
          id={id}
          className={cx(
            "pointer-events-none absolute left-1/2 z-50 -translate-x-1/2 whitespace-nowrap rounded-control",
            "bg-fg px-2 py-1 text-xs text-fg-inverse shadow-popover animate-fade-in",
            placement === "bottom" ? "top-full mt-1.5" : "bottom-full mb-1.5",
          )}
        >
          {content}
        </span>
      ) : null}
    </span>
  );
}
