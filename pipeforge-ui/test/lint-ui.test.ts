/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { join } from "node:path";
import { scanUi } from "@pipeforge/design-tokens/ui-scan";

describe("UI rules", () => {
  it("the sources follow them", () => {
    expect(scanUi(join(__dirname, ".."), ["src"], ["src/ui/"])).toEqual([]);
  });
});
