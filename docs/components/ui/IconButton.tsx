/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { forwardRef, type ButtonHTMLAttributes } from "react";
import { cx } from "./cx";
import { Icon, type IconName } from "./Icon";

/** A square button with an icon; `label` is required and becomes the accessible name. */
export const IconButton = forwardRef<
  HTMLButtonElement,
  { icon: IconName; label: string; size?: "sm" | "md" } & ButtonHTMLAttributes<HTMLButtonElement>
>(function IconButton({ icon, label, size = "md", className, type = "button", children, ...rest }, ref) {
  return (
    <button
      ref={ref}
      type={type}
      aria-label={label}
      className={cx(
        "inline-flex shrink-0 items-center justify-center rounded-control text-fg-muted",
        "transition-colors duration-fast ease-out hover:bg-surface-2 hover:text-fg active:bg-surface-3",
        size === "sm" ? "size-control-sm" : "size-control-md",
        className,
      )}
      {...rest}
    >
      {children ?? <Icon name={icon} />}
    </button>
  );
});
