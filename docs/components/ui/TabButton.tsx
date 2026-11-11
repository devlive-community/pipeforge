/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { forwardRef, type ButtonHTMLAttributes } from "react";
import { cx } from "./cx";

/** A tab in a role="tablist"; the parent manages selection and keyboard navigation. */
export const TabButton = forwardRef<
  HTMLButtonElement,
  { selected: boolean } & Omit<ButtonHTMLAttributes<HTMLButtonElement>, "role" | "type">
>(function TabButton({ selected, className, ...rest }, ref) {
  return (
    <button
      ref={ref}
      type="button"
      role="tab"
      aria-selected={selected}
      tabIndex={selected ? 0 : -1}
      className={cx(
        "h-control-lg shrink-0 px-3 text-sm font-medium whitespace-nowrap transition-colors duration-fast",
        selected ? "text-primary" : "text-fg-muted hover:text-fg",
        className,
      )}
      {...rest}
    />
  );
});
