/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { functionEntries } from "@/lib/generated";
import type { Lang } from "@/lib/i18n";
import { FunctionTable } from "./FunctionTable";

/** Every built-in function, generated from the function catalog; searchable and filterable. */
export function FunctionList({ lang = "en-US" }: { lang?: Lang }) {
  const entries = functionEntries().map((entry) => ({
    name: entry.name,
    category: entry.category,
    syntax: entry.syntax,
    deterministic: entry.deterministic,
    signatures: entry.signatures,
    description: entry.description[lang] ?? entry.description["en-US"] ?? "",
    nulls: entry.nulls ? (entry.nulls[lang] ?? entry.nulls["en-US"] ?? null) : null,
    examples: entry.examples,
  }));
  return <FunctionTable entries={entries} lang={lang} />;
}
