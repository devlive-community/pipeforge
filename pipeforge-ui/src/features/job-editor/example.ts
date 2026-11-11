/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

/** The job the editor starts with when there is no draft. */
export const EXAMPLE_JOB = `version: 1
name: orders-daily
description: Copies yesterday's orders
env:
  parallelism: 2
  variables:
    biz_date: "\${date:yyyy-MM-dd,-1d}"
source:
  - id: orders
    type: fake
transform:
  - id: rename
    type: field-mapper
sink:
  - id: print
    type: console
`;
