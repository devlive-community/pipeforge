/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { RouteObject } from "react-router";
import { NotFoundPage } from "@/features/not-found/NotFoundPage";
import { OverviewPage } from "@/features/overview/OverviewPage";
import { Shell } from "./Shell";

/** The pages of the Web UI; every page renders inside the shell. */
export const ROUTES: RouteObject[] = [
  {
    path: "/",
    element: <Shell />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];
