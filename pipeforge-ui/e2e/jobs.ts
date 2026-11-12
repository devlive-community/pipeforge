/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Jobs shared by the browser tests and the screenshots.

import { EXAMPLE_JOB } from "../src/features/job-editor/example";

/** The example job with a computed column in its field mapper and a comment to keep. */
export const MAPPED_JOB = EXAMPLE_JOB.replace(
  "  - id: rename\n    type: field-mapper\n",
  '  - id: rename           # tidy the columns\n    type: field-mapper\n    mapping:\n      - { expr: "UPPER(name)", to: loud }\n',
);

/** The job of the screenshot: a rename, a computed and retyped column, a constant and snake case. */
export const MAPPED_SHOT = EXAMPLE_JOB.replace(
  "  - id: rename\n    type: field-mapper\n",
  [
    "  - id: rename",
    "    type: field-mapper",
    "    mapping:",
    "      - { from: id, to: order_id }",
    '      - { from: amount, expr: "amount * 100", to: amount_cents, type: "DECIMAL(14, 2)" }',
    '      - { value: "shop", to: source_system }',
    "    name-case: snake",
    "",
  ].join("\n"),
);
