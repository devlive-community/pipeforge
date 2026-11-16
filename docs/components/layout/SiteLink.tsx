/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import Link from "next/link";
import type { ComponentProps } from "react";
import { NavigationLoading } from "./NavigationLoading";

export function SiteLink({ children, ...props }: ComponentProps<typeof Link>) {
  return (
    <Link {...props}>
      {children}
      <NavigationLoading />
    </Link>
  );
}
