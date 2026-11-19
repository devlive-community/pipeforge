/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Metadata } from "next";
import { IntegrationCase } from "@/components/home/IntegrationCase";
import { IntegrationDetails } from "@/components/home/IntegrationDetails";
import { SiteFooter } from "@/components/layout/SiteFooter";
import { SiteHeader } from "@/components/layout/SiteHeader";
import { SiteLink } from "@/components/layout/SiteLink";
import { ButtonLink } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { navigation } from "@/lib/content";
import { CURRENT_VERSION, pageUrl } from "@/lib/content-core.mjs";
import { otherLang, t, toLang } from "@/lib/i18n";
import { integrationLabels } from "@/lib/integration";
import { alternates } from "@/lib/site";

const capabilityIcons = ["database", "sliders", "activity"] as const;

export async function generateMetadata({ params }: { params: Promise<{ lang: string }> }): Promise<Metadata> {
  const lang = toLang((await params).lang);
  return {
    title: { absolute: `PipeForge · ${t(lang).tagline}` },
    description: integrationLabels(lang).heroText,
    alternates: alternates(lang, "/"),
  };
}

export default async function Home({ params }: { params: Promise<{ lang: string }> }) {
  const lang = toLang((await params).lang);
  const dict = t(lang);
  const labels = integrationLabels(lang);
  const docsUrl = `/${lang}/${CURRENT_VERSION}/`;
  const space = lang === "en-US" ? " " : "";
  return (
    <>
      <SiteHeader lang={lang} otherLangHref={`/${otherLang(lang)}/`} sections={navigation(lang)} area="home" />
      <main id="content" className="integration-home">
        <div className="site-container">
          <header className="integration-intro">
            <div>
              <h1>
                {labels.heroTitle[0]}
                {space}
                <span>{labels.heroTitle[1]}</span>
                {space}
                {labels.heroTitle[2]}
              </h1>
              <p>{labels.heroText}</p>
            </div>
            <div className="integration-actions">
              <ButtonLink href={pageUrl(lang, CURRENT_VERSION, "getting-started/quickstart")} variant="primary">
                {dict.sections["getting-started"]}
                <Icon name="arrowRight" size={16} />
              </ButtonLink>
              <ButtonLink href={`${docsUrl}#connectors`}>
                {labels.findConnectors}
                <Icon name="arrowUpRight" size={16} />
              </ButtonLink>
            </div>
          </header>
          <IntegrationCase lang={lang} />
          <section className="integration-capabilities" aria-labelledby="integration-capabilities-heading">
            <header>
              <h2 id="integration-capabilities-heading">{labels.howItWorks}</h2>
              <SiteLink href={docsUrl} className="integration-link">
                {labels.readDocs}
                <Icon name="arrowRight" size={15} />
              </SiteLink>
            </header>
            <ul>
              {labels.capabilities.map(([title, description], index) => (
                <li key={title}>
                  <h3>
                    <Icon name={capabilityIcons[index] ?? "workflow"} size={16} />
                    {title}
                  </h3>
                  <p>{description}</p>
                </li>
              ))}
            </ul>
          </section>
          <IntegrationDetails lang={lang} />
        </div>
      </main>
      <SiteFooter lang={lang} compact />
    </>
  );
}
