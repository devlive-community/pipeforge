/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { ROUTES } from "@/app/routes";

/** Renders the app at `path` with fresh queries that do not retry. */
export function renderApp(path = "/") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(ROUTES, { initialEntries: [path] });
  return {
    router,
    ...render(
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    ),
  };
}

export interface Recorded {
  path: string;
  search: string;
  body: string;
  language: string | null;
}

/**
 * Replaces fetch with a fake server: `routes` maps API paths to bodies, to [status, body], or to a
 * function of the request body returning either. Unknown paths answer 404. Returns the requests made.
 */
export function fakeServer(routes: Record<string, unknown>): Recorded[] {
  const requests: Recorded[] = [];
  vi.stubGlobal("fetch", async (input: Request | string | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), "http://ui.test"), init);
    const url = new URL(request.url);
    const path = url.pathname;
    const sent = await request.clone().text();
    requests.push({ path, search: url.search, body: sent, language: request.headers.get("Accept-Language") });
    const route = routes[path];
    const answer = typeof route === "function" ? (route as (body: string) => unknown)(sent) : route;
    const [status, body] =
      Array.isArray(answer) && typeof answer[0] === "number" ? answer : [answer ? 200 : 404, answer];
    return new Response(JSON.stringify(body ?? { code: "PF-CONF-0007", message: "no endpoint" }), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  });
  return requests;
}

/** API answers of a server with one plugin and one rejected package. */
export const SERVER = {
  "/api/v1/version": { version: "0.1.0", connectorApi: "0.1.0", java: "21.0.10" },
  "/api/v1/plugins": {
    plugins: [{ id: "fake", name: "Fake", version: "0.1.0", apiVersion: "0.x", components: [], drivers: [] }],
    unavailable: [{ location: "plugins/broken-1.0.0", reason: "provides must list at least one component" }],
  },
  "/api/v1/functions": [{ name: "ABS" }, { name: "COALESCE" }, { name: "LENGTH" }],
  "/api/v1/errors": [{ code: "PF-CONF-0001" }, { code: "PF-CONF-0002" }],
};
