/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { RunQuery } from "@pipeforge/client";
import { useQuery } from "@tanstack/react-query";
import { usePreferences } from "@/state/preferences";
import { api } from "./client";

/** Server and connector API versions. */
export function useVersion() {
  return useQuery({ queryKey: ["version"], queryFn: api.getVersion });
}

/** Installed plugins and the packages that could not be loaded. */
export function usePlugins() {
  return useQuery({ queryKey: ["plugins"], queryFn: api.listPlugins });
}

/** Built-in functions; the texts follow the UI language. */
export function useFunctions() {
  const language = usePreferences((s) => s.language);
  return useQuery({ queryKey: ["functions", language], queryFn: api.listFunctions });
}

/** Registered error codes; the texts follow the UI language. */
export function useErrorCodes() {
  const language = usePreferences((s) => s.language);
  return useQuery({ queryKey: ["errors", language], queryFn: api.listErrorCodes });
}

/** The JSON Schema of jobs with the options of the installed components; fetched once. */
/** How often the run pages ask again; TanStack Query skips the polls while the page is hidden. */
export const RUN_POLL_MS = 2000;

export function useRuns(query: RunQuery) {
  return useQuery({
    queryKey: ["runs", query],
    queryFn: () => api.listRuns(query),
    refetchInterval: RUN_POLL_MS,
    refetchIntervalInBackground: false,
    placeholderData: (previous) => previous,
  });
}

export function useJobSchema() {
  return useQuery({ queryKey: ["job-schema"], queryFn: api.getJobSchema, staleTime: Infinity });
}

/** The options of one component; only fetched once a component is chosen. */
export function useComponent(
  type: "source" | "transform" | "sink" | undefined,
  identifier: string | undefined,
  plugin?: string,
) {
  return useQuery({
    queryKey: ["component", type, identifier, plugin],
    queryFn: () => api.getComponent(type as "source", identifier as string, plugin),
    enabled: Boolean(type && identifier),
  });
}
