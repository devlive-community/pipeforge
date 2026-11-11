/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useEffect } from "react";
import { useLocation } from "react-router";

/** Scrolls to the element named by the address hash (e.g. #coalesce) once the page content is ready. */
export function useScrollToHash(ready: boolean) {
  const { hash } = useLocation();
  useEffect(() => {
    if (!ready || hash.length < 2) return;
    const element = document.getElementById(decodeURIComponent(hash.slice(1)));
    element?.scrollIntoView?.({ block: "start" });
    element?.focus?.({ preventScroll: true });
  }, [ready, hash]);
}
