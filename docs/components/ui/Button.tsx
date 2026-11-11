/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import Link from "next/link";
import type { ComponentProps, ReactNode } from "react";
import { cx } from "./cx";

type Variant = "primary" | "secondary" | "ghost";
type Size = "sm" | "md" | "lg";

const variants: Record<Variant, string> = {
  primary: "bg-primary text-primary-fg hover:bg-primary-hover active:bg-primary-active",
  secondary: "border border-border bg-surface text-fg hover:bg-surface-2 active:bg-surface-3",
  ghost: "text-fg-muted hover:bg-surface-2 hover:text-fg active:bg-surface-3",
};

const sizes: Record<Size, string> = {
  sm: "h-control-sm px-2.5 text-sm",
  md: "h-control-md px-3 text-sm",
  lg: "h-control-lg px-4 text-base",
};

export function buttonClass(
  variant: Variant = "secondary",
  size: Size = "md",
  className?: string,
  justify: "center" | "between" | "start" = "center",
) {
  return cx(
    "inline-flex shrink-0 items-center gap-2 whitespace-nowrap rounded-control font-medium",
    justify === "center" ? "justify-center" : justify === "between" ? "justify-between" : "justify-start",
    "transition-colors duration-fast ease-out disabled:pointer-events-none disabled:opacity-50",
    variants[variant],
    sizes[size],
    className,
  );
}

export function Button({
  variant = "secondary",
  size = "md",
  justify = "center",
  className,
  type = "button",
  ...rest
}: { variant?: Variant; size?: Size; justify?: "center" | "between" | "start" } & ComponentProps<"button">) {
  return <button type={type} className={buttonClass(variant, size, className, justify)} {...rest} />;
}

export function ButtonLink({
  href,
  variant = "secondary",
  size = "md",
  className,
  external,
  children,
}: {
  href: string;
  variant?: Variant;
  size?: Size;
  className?: string;
  external?: boolean;
  children: ReactNode;
}) {
  if (external) {
    return (
      <a href={href} target="_blank" rel="noreferrer" className={buttonClass(variant, size, className)}>
        {children}
      </a>
    );
  }
  return (
    <Link href={href} className={buttonClass(variant, size, className)}>
      {children}
    </Link>
  );
}
