/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { createClient } from "@pipeforge/client";
import { usePreferences } from "@/state/preferences";

/** The API client; server messages follow the language of the UI. */
export const api = createClient({
  // Same origin as the page: the server hosts the UI, and the dev server forwards /api.
  baseUrl: window.location.origin,
  language: () => usePreferences.getState().language,
  // Looked up on every call, so that tests can replace the global fetch.
  fetch: (input, init) => globalThis.fetch(input, init),
});
