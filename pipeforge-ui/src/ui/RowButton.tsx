/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ComponentProps } from "react";
import { cx } from "./cx";

/**
 * A full-width row of a list that can be selected, such as a column of a mapping; {@code selected}
 * is announced with aria-pressed. Further attributes (data-*, aria-*) go to the button.
 */
export function RowButton({
  selected = false,
  highlighted = false,
  className,
  children,
  type = "button",
  ...rest
}: { selected?: boolean; highlighted?: boolean } & ComponentProps<"button">) {
  return (
    <button
      type={type}
      aria-pressed={selected}
      className={cx(
        "flex min-h-9 w-full items-center gap-2 rounded-control border px-2 py-1 text-left text-sm outline-none",
        "transition-colors duration-fast ease-out focus-visible:ring-2 focus-visible:ring-primary",
        selected ? "border-primary bg-primary-soft" : "border-border bg-surface hover:border-primary",
        highlighted && "border-dashed",
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  );
}
