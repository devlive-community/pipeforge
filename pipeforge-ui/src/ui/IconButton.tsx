/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ComponentProps } from "react";
import { cx } from "./cx";
import { Icon, type IconName } from "./Icon";
import { Tooltip } from "./Tooltip";

/** A square button with an icon; `label` is its accessible name and its tooltip. */
export function IconButton({
  icon,
  label,
  size = "md",
  className,
  type = "button",
  ...rest
}: { icon: IconName; label: string; size?: "sm" | "md" } & ComponentProps<"button">) {
  return (
    <Tooltip content={label} describe={false}>
      <button
        type={type}
        aria-label={label}
        className={cx(
          "inline-flex shrink-0 items-center justify-center rounded-control text-fg-muted",
          "transition-colors duration-fast ease-out hover:bg-surface-2 hover:text-fg active:bg-surface-3",
          "disabled:cursor-not-allowed disabled:opacity-50",
          size === "sm" ? "size-control-sm" : "size-control-md",
          className,
        )}
        {...rest}
      >
        <Icon name={icon} />
      </button>
    </Tooltip>
  );
}
