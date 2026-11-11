/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { errorEntries } from "@/lib/generated";
import type { Lang } from "@/lib/i18n";
import { ErrorTable } from "./ErrorTable";

/** Every registered error code, generated from the error registry; searchable and filterable. */
export function ErrorList({ lang = "en-US" }: { lang?: Lang }) {
  const entries = errorEntries().map((entry) => ({
    code: entry.code,
    name: entry.name,
    category: entry.category,
    kind: entry.kind,
    deprecated: entry.deprecated,
    message: entry.message[lang] ?? entry.message["en-US"] ?? "",
    hint: entry.hint[lang] ?? entry.hint["en-US"] ?? "",
  }));
  return <ErrorTable entries={entries} lang={lang} />;
}
