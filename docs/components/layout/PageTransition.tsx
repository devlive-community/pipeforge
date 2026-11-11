/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

/** Replays the entrance animation of the main content on every navigation. */
export function PageTransition({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  return (
    <div key={pathname} className="animate-fade-up">
      {children}
    </div>
  );
}
