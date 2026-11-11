/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useEffect, useState } from "react";
import { EXAMPLE_JOB } from "./example";

/** Key in localStorage that keeps the editor's draft. */
export const DRAFT_STORAGE_KEY = "pipeforge-job-draft";

function readDraft(): string {
  try {
    return localStorage.getItem(DRAFT_STORAGE_KEY) ?? EXAMPLE_JOB;
  } catch {
    return EXAMPLE_JOB;
  }
}

/** The definition being edited, kept in the browser so a reload does not lose it. */
export function useDraft(): [string, (value: string) => void] {
  const [draft, setDraft] = useState(readDraft);
  useEffect(() => {
    const timer = setTimeout(() => {
      try {
        localStorage.setItem(DRAFT_STORAGE_KEY, draft);
      } catch {
        // Storage refused (private window): the draft lasts for this page only.
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [draft]);
  return [draft, setDraft];
}
