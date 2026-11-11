/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api } from "@/api/client";
import { usePreferences } from "@/state/preferences";

/** `value` once it has not changed for `delay` milliseconds. */
export function useDebounced<T>(value: T, delay: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return settled;
}

/** Delay between the last keystroke and the check. */
export const CHECK_DELAY_MS = 400;

/**
 * Validates the definition while it is edited and explains it once it is valid. Earlier results
 * stay visible while a new check runs, so the views do not flicker.
 */
export function useJobCheck(definition: string) {
  const language = usePreferences((s) => s.language);
  const settled = useDebounced(definition, CHECK_DELAY_MS);
  const enabled = settled.trim() !== "";
  const validation = useQuery({
    queryKey: ["validate", settled, language],
    queryFn: () => api.validateJob({ definition: settled }),
    enabled,
    placeholderData: keepPreviousData,
  });
  const plan = useQuery({
    queryKey: ["explain", settled, language],
    queryFn: () => api.explainJob({ definition: settled }),
    enabled: enabled && validation.data?.valid === true,
    placeholderData: keepPreviousData,
  });
  return {
    checking: settled !== definition || validation.isFetching,
    validation,
    plan: validation.data?.valid ? plan : undefined,
  };
}
