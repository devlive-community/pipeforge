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
import { ButtonLink } from "@/components/ui/Button";
import { navigation } from "@/lib/content";
import { CURRENT_VERSION, pageUrl } from "@/lib/content-core.mjs";
import { otherLang, t, toLang } from "@/lib/i18n";
import { alternates } from "@/lib/site";

export async function generateMetadata({ params }: { params: Promise<{ lang: string }> }): Promise<Metadata> {
  const lang = toLang((await params).lang);
  return { title: { absolute: `PipeForge · ${t(lang).tagline}` }, alternates: alternates(lang, "/") };
}

export default async function Home({ params }: { params: Promise<{ lang: string }> }) {
  const lang = toLang((await params).lang);
  const dict = t(lang);
  const sections = navigation(lang);
  return (
    <>
      <SiteHeader lang={lang} otherLangHref={`/${otherLang(lang)}/`} sections={sections} />
      <main id="content">
        <section className="mx-auto grid max-w-screen-xl items-center gap-10 px-4 pt-16 pb-12 sm:px-6 lg:grid-cols-2 lg:pt-24">
          <div className="animate-fade-up">
            <p className="text-sm font-semibold text-primary">{dict.tagline}</p>
            <h1 className="mt-3 text-4xl font-bold tracking-tight text-balance text-fg sm:text-5xl">
              {dict.heroTitle}
            </h1>
            <p className="mt-5 max-w-xl text-lg leading-8 text-fg-muted">{dict.heroText}</p>
            <div className="mt-8 flex flex-wrap gap-3">
              <ButtonLink href={pageUrl(lang, CURRENT_VERSION, "getting-started/overview")} variant="primary" size="lg">
                {dict.getStarted}
              </ButtonLink>
              <ButtonLink href={REPOSITORY_URL} external size="lg">
                {dict.viewSource}
              </ButtonLink>
            </div>
          </div>
          <HeroDag label="Source → Transform → Sink" />
        </section>
        <section className="mx-auto max-w-screen-xl px-4 pb-24 sm:px-6">
          <FeatureGrid features={dict.features} />
        </section>
      </main>
      <SiteFooter lang={lang} />
    </>
  );
}
