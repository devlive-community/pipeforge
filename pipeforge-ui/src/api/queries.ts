/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

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
