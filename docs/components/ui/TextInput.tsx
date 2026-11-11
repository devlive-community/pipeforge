/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { forwardRef, type InputHTMLAttributes } from "react";
import { cx } from "./cx";

/** Single-line text input; always needs an accessible label. */
export const TextInput = forwardRef<
  HTMLInputElement,
  { label: string } & Omit<InputHTMLAttributes<HTMLInputElement>, "type">
>(function TextInput({ label, className, ...rest }, ref) {
  return (
    <input
      ref={ref}
      type="text"
      aria-label={label}
      className={cx(
        "h-control-lg w-full min-w-0 bg-transparent text-base text-fg outline-none placeholder:text-fg-subtle",
        className,
      )}
      {...rest}
    />
  );
});
