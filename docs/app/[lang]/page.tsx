/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Metadata } from "next";
import { FeatureGrid } from "@/components/home/FeatureGrid";
import { HeroDag } from "@/components/home/HeroDag";
import { SiteFooter } from "@/components/layout/SiteFooter";
import { REPOSITORY_URL, SiteHeader } from "@/components/layout/SiteHeader";
import { Screenshot } from "@/components/mdx/Screenshot";
import { ButtonLink } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { SiteLink } from "@/components/layout/SiteLink";
import { navigation } from "@/lib/content";
import { CURRENT_VERSION, pageUrl } from "@/lib/content-core.mjs";
import { otherLang, t, toLang } from "@/lib/i18n";
import { alternates } from "@/lib/site";

const resourceIcons = {
  "getting-started/quickstart": "terminal",
  "reference/job-dsl": "code",
  plugins: "box",
} as const;

export async function generateMetadata({ params }: { params: Promise<{ lang: string }> }): Promise<Metadata> {
  const lang = toLang((await params).lang);
  return { title: { absolute: `PipeForge · ${t(lang).tagline}` }, alternates: alternates(lang, "/") };
}

export default async function Home({ params }: { params: Promise<{ lang: string }> }) {
  const lang = toLang((await params).lang);
  const dict = t(lang);
  const docsUrl = `/${lang}/${CURRENT_VERSION}/`;
  return (
    <>
      <SiteHeader lang={lang} otherLangHref={`/${otherLang(lang)}/`} sections={navigation(lang)} area="home" />
      <main id="content">
        <section className="home-intro">
          <div className="site-container home-hero">
            <div>
              <p className="home-kicker">
                <Icon name="workflow" size={16} />
                {dict.tagline}
              </p>
              <h1>
                {dict.heroTitle[0]} <span>{dict.heroTitle[1]}</span>
              </h1>
              <p className="home-summary">{dict.heroText}</p>
              <div className="home-actions">
                <ButtonLink
                  href={pageUrl(lang, CURRENT_VERSION, "getting-started/quickstart")}
                  variant="primary"
                  size="lg"
                  className="home-action"
                >
                  {dict.getStarted}
                  <Icon name="arrowRight" size={17} />
                </ButtonLink>
                <ButtonLink href={REPOSITORY_URL} external size="lg" className="home-action">
                  <Icon name="github" />
                  {dict.viewSource}
                </ButtonLink>
              </div>
            </div>
            <HeroDag lang={lang} label={dict.playground} stages={dict.pipelineStages} />
          </div>
          <nav className="site-container home-resources" aria-label={dict.exploreDocs}>
            {dict.homepageResources.map(([id, title, description]) => (
              <SiteLink key={id} href={id === "plugins" ? `${docsUrl}#plugins` : pageUrl(lang, CURRENT_VERSION, id)}>
                <span className="resource-icon">
                  <Icon name={resourceIcons[id]} size={20} />
                </span>
                <span className="resource-copy">
                  <strong>{title}</strong>
                  <span>{description}</span>
                </span>
                <Icon name="arrowUpRight" size={17} className="resource-arrow" />
              </SiteLink>
            ))}
          </nav>
        </section>
        <section className="site-container home-features">
          <h2>{dict.featureTitle}</h2>
          <FeatureGrid features={dict.features} />
        </section>
        <section className="home-editor">
          <div className="site-container home-editor-layout">
            <div>
              <h2>{dict.editorTitle}</h2>
              <p>{dict.editorText}</p>
              <SiteLink
                href={pageUrl(lang, CURRENT_VERSION, "guides/job-editor")}
                className="inline-flex items-center gap-2 font-semibold text-primary hover:text-primary-hover"
              >
                {dict.editorLink}
                <Icon name="arrowRight" />
              </SiteLink>
            </div>
            <Screenshot
              name="job-editor"
              alt={
                lang === "zh-CN"
                  ? "显示 YAML、作业拓扑和执行计划的 PipeForge 作业编辑器"
                  : "PipeForge job editor showing YAML, the job graph and its execution plan"
              }
              lang={lang}
            />
          </div>
        </section>
      </main>
      <SiteFooter lang={lang} />
    </>
  );
}
