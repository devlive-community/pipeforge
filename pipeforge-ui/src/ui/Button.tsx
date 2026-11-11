/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ComponentProps } from "react";
import { cx } from "./cx";
import { Icon, type IconName } from "./Icon";
import { Spinner } from "./Spinner";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "sm" | "md" | "lg";

const variants: Record<ButtonVariant, string> = {
  primary: "bg-primary text-primary-fg hover:bg-primary-hover active:bg-primary-active",
  secondary: "border border-border bg-surface text-fg hover:bg-surface-2 active:bg-surface-3",
  ghost: "text-fg-muted hover:bg-surface-2 hover:text-fg active:bg-surface-3",
  danger: "bg-danger text-danger-fg hover:opacity-90 active:opacity-80",
};

const sizes: Record<ButtonSize, string> = {
  sm: "h-control-sm px-2.5 text-sm",
  md: "h-control-md px-3 text-sm",
  lg: "h-control-lg px-4 text-base",
};

/** The classes of a button, also for links that look like buttons. */
export function buttonClass(variant: ButtonVariant = "secondary", size: ButtonSize = "md", className?: string) {
  return cx(
    "inline-flex shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded-control font-medium",
    "transition-colors duration-fast ease-out disabled:cursor-not-allowed disabled:opacity-50",
    variants[variant],
    sizes[size],
    className,
  );
}

/**
 * A button. While `loading` it shows a spinner, is disabled and reports aria-busy; state is
 * shown with color and opacity transitions only, never with a ripple.
 */
export function Button({
  variant = "secondary",
  size = "md",
  loading = false,
  icon,
  className,
  type = "button",
  disabled,
  children,
  ...rest
}: {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  icon?: IconName;
} & ComponentProps<"button">) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={buttonClass(variant, size, className)}
      {...rest}
    >
      {loading ? <Spinner /> : icon ? <Icon name={icon} /> : null}
      {children}
    </button>
  );
}
