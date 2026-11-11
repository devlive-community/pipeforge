/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Metadata } from "next";
import Link from "next/link";
import { DocShell } from "@/components/layout/DocShell";
import { navigation } from "@/lib/content";
import { VERSIONS } from "@/lib/content-core.mjs";
import { otherLang, t, toLang } from "@/lib/i18n";
import { alternates } from "@/lib/site";

export const dynamicParams = false;

export function generateStaticParams() {
  return VERSIONS.map((version) => ({ version }));
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ lang: string; version: string }>;
}): Promise<Metadata> {
  const { lang, version } = await params;
  return { title: t(toLang(lang)).docs, alternates: alternates(lang, `/${version}/`) };
}

/** Overview of every documentation page in this version. */
export default async function DocsIndex({ params }: { params: Promise<{ lang: string; version: string }> }) {
  const { lang: rawLang, version } = await params;
  const lang = toLang(rawLang);
  const dict = t(lang);
  const sections = navigation(lang);
  return (
    <DocShell lang={lang} sections={sections} otherLangHref={`/${otherLang(lang)}/${version}/`}>
      <div className="mx-auto max-w-3xl">
        <h1 className="text-3xl font-bold tracking-tight text-fg">{dict.docs}</h1>
        <div className="mt-8 grid gap-6 sm:grid-cols-2">
          {sections.map((section) => (
            <section key={section.key} className="rounded-card border border-border p-5">
              <h2 className="font-semibold text-fg">{dict.sections[section.key as keyof typeof dict.sections]}</h2>
              <ul className="mt-3 space-y-1.5">
                {section.items.map((item) => (
                  <li key={item.id}>
                    <Link href={item.url} className="text-sm text-primary hover:text-primary-hover">
                      {item.title}
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      </div>
    </DocShell>
  );
}
