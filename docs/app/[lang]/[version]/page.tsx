/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Metadata } from "next";
import { ConnectorMark } from "@/components/home/ConnectorMark";
import { Search } from "@/components/layout/Search";
import { SiteFooter } from "@/components/layout/SiteFooter";
import { SiteHeader } from "@/components/layout/SiteHeader";
import { SiteLink } from "@/components/layout/SiteLink";
import { CodeBlock } from "@/components/mdx/CodeBlock";
import { Icon, type IconName } from "@/components/ui/Icon";
import { navigation } from "@/lib/content";
import { VERSIONS, pageUrl } from "@/lib/content-core.mjs";
import { otherLang, t, toLang } from "@/lib/i18n";
import { integrationLabels } from "@/lib/integration";
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

const stages = [
  {
    name: "Source",
    entries: [
      { page: "plugins/source/mysql", connector: "mysql", label: 0 },
      { page: "plugins/source/postgres", connector: "postgresql", label: 1 },
    ],
  },
  {
    name: "Transform",
    entries: [
      { page: "plugins/transform/field-mapper", icon: "sliders", label: 2 },
      { page: "reference/expression-syntax", icon: "code", label: 3 },
    ],
  },
  {
    name: "Sink",
    entries: [
      { page: "plugins/sink/mysql", connector: "mysql", label: 4 },
      { page: "plugins/sink/postgres", connector: "postgresql", label: 5 },
    ],
  },
] as const;

const startLinks = [
  { page: "getting-started/quickstart", icon: "terminal", label: 0 },
  { page: "reference/job-dsl", icon: "workflow", label: 1 },
  { page: "concepts/plan-and-explain", icon: "activity", label: 2 },
] as const;

const referenceLinks = ["reference/job-dsl", "operations/deployment", "reference/metrics"] as const;
const localCommand = "pipeforge run --local \\\n  examples/fake-to-console.yaml";

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
  const labels = integrationLabels(toLang(lang)).docs;
  return { title: labels.title, description: labels.intro, alternates: alternates(lang, `/${version}/`) };
}

export default async function DocsIndex({ params }: { params: Promise<{ lang: string; version: string }> }) {
  const { lang: rawLang, version } = await params;
  const lang = toLang(rawLang);
  const dict = t(lang);
  const labels = integrationLabels(lang).docs;
  const sections = navigation(lang);
  return (
    <>
      <SiteHeader
        lang={lang}
        version={version}
        sections={sections}
        otherLangHref={`/${otherLang(lang)}/${version}/`}
        area="docs-index"
      />
      <main id="content" className="integration-docs">
        <div className="site-container">
          <header className="integration-docs-intro">
            <div>
              <h1>{labels.title}</h1>
              <p>{labels.intro}</p>
            </div>
            <Search lang={lang} labels={{ ...dict, searchPlaceholder: labels.search }} field />
          </header>
          <div className="integration-docs-grid">
            <section id="connectors" aria-labelledby="integration-connectors-heading">
              <h2 id="integration-connectors-heading">{labels.stagesTitle}</h2>
              <div className="integration-stages">
                {stages.map(({ name, entries }, index) => (
                  <section className="integration-doc-stage" data-stage={name} key={name}>
                    <div>
                      <span>{name}</span>
                      <h3>{labels.stages[index]}</h3>
                    </div>
                    <ul>
                      {entries.map((entry) => (
                        <li key={entry.page}>
                          <SiteLink href={pageUrl(lang, version, entry.page)}>
                            <span className="connector-mark">
                              {"connector" in entry ? (
                                <ConnectorMark connector={entry.connector} />
                              ) : (
                                <Icon name={entry.icon} size={16} />
                              )}
                            </span>
                            <span>
                              <strong>{labels.entries[entry.label][0]}</strong>
                              <small>{labels.entries[entry.label][1]}</small>
                            </span>
                            <Icon name="chevron" size={14} />
                          </SiteLink>
                        </li>
                      ))}
                    </ul>
                  </section>
                ))}
              </div>
            </section>
            <section id="start" aria-labelledby="integration-start-heading">
              <h2 id="integration-start-heading">{labels.firstJob}</h2>
              <div className="integration-start">
                <p>{labels.firstJobText}</p>
                <ul>
                  {startLinks.map(({ page, icon, label }) => (
                    <li key={page}>
                      <SiteLink href={pageUrl(lang, version, page)}>
                        <Icon name={icon} size={15} />
                        {labels.startLinks[label]}
                      </SiteLink>
                    </li>
                  ))}
                </ul>
                <CodeBlock lang={lang} language="bash" code={localCommand} />
              </div>
            </section>
          </div>
          <ul className="integration-references" aria-label={dict.sections.reference}>
            {referenceLinks.map((page, index) => (
              <li key={page}>
                <SiteLink href={pageUrl(lang, version, page)}>
                  <span>
                    <strong>{labels.reference[index]?.[0]}</strong>
                    <small>{labels.reference[index]?.[1]}</small>
                  </span>
                  <Icon name="arrowRight" size={15} />
                </SiteLink>
              </li>
            ))}
          </ul>
          <section className="integration-topics" aria-labelledby="integration-topics-heading">
            <h2 id="integration-topics-heading">{dict.browseDocs}</h2>
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
          </section>
        </div>
      </main>
      <SiteFooter lang={lang} version={version} compact />
    </>
  );
}
