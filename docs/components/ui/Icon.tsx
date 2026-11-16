/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { SVGProps } from "react";

const paths = {
  search: "M11 19a8 8 0 1 1 0-16 8 8 0 0 1 0 16Zm10 2-4.35-4.35",
  sun: "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10ZM12 1v2m0 18v2M4.22 4.22l1.42 1.42m12.72 12.72 1.42 1.42M1 12h2m18 0h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42",
  moon: "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79Z",
  monitor: "M4 4h16v12H4zM8 20h8m-4-4v4",
  copy: "M9 9h11v11H9zM5 15H4V4h11v1",
  check: "M20 6 9 17l-5-5",
  menu: "M3 6h18M3 12h18M3 18h18",
  close: "M18 6 6 18M6 6l12 12",
  chevron: "m9 18 6-6-6-6",
  arrowLeft: "M19 12H5m7 7-7-7 7-7",
  arrowRight: "M5 12h14m-7-7 7 7-7 7",
  arrowUpRight: "M7 17 17 7M7 7h10v10",
  book: "M4 19V5a2 2 0 0 1 2-2h14v18H6a2 2 0 1 1 0-4h14M8 7h8m-8 4h5",
  terminal: "M4 5h16v14H4zM8 9l3 3-3 3m5 0h3",
  database:
    "M20 6c0 1.7-3.6 3-8 3S4 7.7 4 6s3.6-3 8-3 8 1.3 8 3ZM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3",
  layers: "m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5",
  workflow: "M3 4h6v6H3zm12 10h6v6h-6zM6 10v5a2 2 0 0 0 2 2h7m2-13h4m-2-2v4",
  box: "m12 3 9 5v8l-9 5-9-5V8l9-5ZM3 8l9 5 9-5m-9 5v8M7.5 5.5l9 5",
  code: "m8 6-6 6 6 6m8-12 6 6-6 6m-3-15-2 18",
  activity: "M2 12h5l3-7 4 14 3-7h5",
  sliders: "M4 7h8m4 0h4M4 17h4m4 0h8M12 4v6M8 14v6",
  list: "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01",
  edit: "m15 5 4 4M4 20l5-1L21 7l-4-4L5 15l-1 5Z",
  github:
    "M9 19c-5 1.5-5-2.5-7-3m14 6v-3.87a3.37 3.37 0 0 0-.94-2.61c3.14-.35 6.44-1.54 6.44-7A5.44 5.44 0 0 0 20 4.77 5.07 5.07 0 0 0 19.91 1S18.73.65 16 2.48a13.38 13.38 0 0 0-7 0C6.27.65 5.09 1 5.09 1A5.07 5.07 0 0 0 5 4.77a5.44 5.44 0 0 0-1.5 3.78c0 5.42 3.3 6.61 6.44 7A3.37 3.37 0 0 0 9 18.13V22",
  info: "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Zm0-6v-4m0-4h.01",
  alert: "M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0ZM12 9v4m0 4h.01",
  globe:
    "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20ZM2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10Z",
} as const;

export type IconName = keyof typeof paths;

export function Icon({ name, size = 16, ...rest }: { name: IconName; size?: number } & SVGProps<SVGSVGElement>) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      <path d={paths[name]} />
    </svg>
  );
}
