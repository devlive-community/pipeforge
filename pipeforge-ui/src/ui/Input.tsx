/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useId, type ComponentProps, type ReactNode } from "react";
import { cx } from "./cx";
import { Icon, type IconName } from "./Icon";

/**
 * A text input with a visible or hidden label, an optional leading icon and an error message.
 * The error is announced with the input (aria-invalid and aria-describedby).
 */
export function Input({
  label,
  hideLabel = false,
  icon,
  error,
  className,
  id,
  ...rest
}: {
  label: ReactNode;
  hideLabel?: boolean;
  icon?: IconName;
  error?: string;
} & Omit<ComponentProps<"input">, "children">) {
  const generated = useId();
  const inputId = id ?? generated;
  const errorId = `${inputId}-error`;
  return (
    <div className={cx("min-w-0", className)}>
      <label htmlFor={inputId} className={hideLabel ? "sr-only" : "mb-1 block text-xs font-medium text-fg-muted"}>
        {label}
      </label>
      <div
        className={cx(
          "flex h-control-md items-center gap-2 rounded-control border bg-surface px-2.5",
          "transition-colors duration-fast ease-out focus-within:border-primary",
          error ? "border-danger" : "border-border",
        )}
      >
        {icon ? <Icon name={icon} className="shrink-0 text-fg-subtle" /> : null}
        <input
          id={inputId}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          className="h-full w-full min-w-0 bg-transparent text-sm text-fg outline-none placeholder:text-fg-subtle"
          {...rest}
        />
      </div>
      {error ? (
        <p id={errorId} className="mt-1 text-xs text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
