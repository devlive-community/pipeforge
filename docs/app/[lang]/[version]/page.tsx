/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Metadata } from "next";
import { SiteLink } from "@/components/layout/SiteLink";
import { DocShell } from "@/components/layout/DocShell";
import { ButtonLink } from "@/components/ui/Button";
import { Icon, type IconName } from "@/components/ui/Icon";
import { navigation } from "@/lib/content";
import { VERSIONS, pageUrl } from "@/lib/content-core.mjs";
import { otherLang, t, toLang } from "@/lib/i18n";
import { alternates } from "@/lib/site";

const sectionIcons = {
  "getting-started": "terminal",
  concepts: "layers",
  guides: "book",
  reference: "code",
  plugins: "box",
  operations: "activity",
  development: "workflow",
} satisfies Record<string, IconName>;

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
  const dict = t(toLang(lang));
  return { title: dict.docs, description: dict.docsDescription, alternates: alternates(lang, `/${version}/`) };
}

export default async function DocsIndex({ params }: { params: Promise<{ lang: string; version: string }> }) {
  const { lang: rawLang, version } = await params;
  const lang = toLang(rawLang);
  const dict = t(lang);
  const sections = navigation(lang);
  return (
    <DocShell lang={lang} version={version} sections={sections} otherLangHref={`/${otherLang(lang)}/${version}/`}>
      <div className="docs-index">
        <header className="docs-index-intro">
          <span className="resource-icon">
            <Icon name="book" size={22} />
          </span>
          <h1>{dict.docs}</h1>
          <p>{dict.docsDescription}</p>
          <ButtonLink
            href={pageUrl(lang, version, "getting-started/quickstart")}
            variant="primary"
            size="lg"
            className="home-action"
          >
            {dict.getStarted}
            <Icon name="arrowRight" size={17} />
          </ButtonLink>
        </header>
        <h2 className="docs-index-heading">{dict.browseDocs}</h2>
        <div className="docs-topic-grid">
          {sections.map((section) => {
            const key = section.key as keyof typeof sectionIcons;
            return (
              <section key={key} id={key} className="docs-topic">
                <div className="docs-topic-header">
                  <span className="resource-icon">
                    <Icon name={sectionIcons[key]} size={18} />
                  </span>
                  <div>
                    <h3>{dict.sections[key]}</h3>
                    <p>{dict.sectionSummaries[key]}</p>
                  </div>
                </div>
                <ul>
                  {section.items.map((item) => (
                    <li key={item.id}>
                      <SiteLink href={item.url}>
                        {item.title}
                        <Icon name="arrowUpRight" size={14} />
                      </SiteLink>
                    </li>
                  ))}
                </ul>
              </section>
            );
          })}
        </div>
      </div>
    </DocShell>
  );
}
