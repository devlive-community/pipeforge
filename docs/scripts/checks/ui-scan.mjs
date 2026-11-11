/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// The UI rule scan of the documentation site: the shared rules of @pipeforge/design-tokens,
// with components/ui as the component library.

import { RULES, scanSource as scan, scanUi as scanTree } from "@pipeforge/design-tokens/ui-scan";

export { RULES };

const COMPONENTS = ["components/ui/"];

/** Scans one file's text; `path` is relative to the docs root with forward slashes. */
export function scanSource(path, text) {
  return scan(path, text, COMPONENTS);
}

/** Scans the site sources under the given docs root. */
export function scanUi(root, dirs = ["app", "components", "styles"]) {
  return scanTree(root, dirs, COMPONENTS);
}
