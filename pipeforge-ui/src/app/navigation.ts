/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { MessageKey } from "@/i18n";
import type { IconName } from "@/ui";

/** The pages in the main navigation; each feature adds its entry together with its page. */
export const NAVIGATION: { to: string; label: MessageKey; icon: IconName }[] = [
  { to: "/", label: "nav.overview", icon: "home" },
];
