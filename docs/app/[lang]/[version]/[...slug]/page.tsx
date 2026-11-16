/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Metadata } from "next";
import { SiteLink } from "@/components/layout/SiteLink";
import { notFound } from "next/navigation";
import { DocShell } from "@/components/layout/DocShell";
import { REPOSITORY_URL } from "@/components/layout/SiteHeader";
import { Toc } from "@/components/layout/Toc";
import { Icon } from "@/components/ui/Icon";
import { allPages, findPage, navigation, neighbours } from "@/lib/content";
import { VERSIONS, pageUrl } from "@/lib/content-core.mjs";
import { otherLang, t, toLang } from "@/lib/i18n";
import { renderMdx } from "@/lib/mdx";
import { alternates } from "@/lib/site";

interface Params {
  lang: string;
  version: string;
  slug: string[];
}

export const dynamicParams = false;

export function generateStaticParams({ params }: { params: { lang: string } }) {
  return VERSIONS.flatMap((version) =>
    allPages()
      .filter((page) => page.lang === params.lang)
      .map((page) => ({ version, slug: page.id.split("/") })),
  );
}

export async function generateMetadata({ params }: { params: Promise<Params> }): Promise<Metadata> {
  const { lang, version, slug } = await params;
  const page = findPage(toLang(lang), slug.join("/"));
  if (!page) return {};
  return {
    title: page.frontmatter.title,
    description: page.frontmatter.description,
    alternates: alternates(lang, `/${version}/${page.id}/`),
  };
}

export default async function DocPage({ params }: { params: Promise<Params> }) {
  const { lang: rawLang, version, slug } = await params;
  const lang = toLang(rawLang);
  const id = slug.join("/");
  const page = findPage(lang, id);
  if (!page) notFound();
  const dict = t(lang);
  const content = await renderMdx(page.body, { lang, pageId: id });
  const { previous, next } = neighbours(lang, id);
  const tocHeadings = page.headings.filter((h) => h.depth <= 3);
  const section = id.split("/")[0] as keyof typeof dict.sections;
  const editHref = `${REPOSITORY_URL}/edit/dev/docs/content/${page.file}`;

  return (
    <DocShell
      lang={lang}
      version={version}
      sections={navigation(lang)}
      currentId={id}
      otherLangHref={pageUrl(otherLang(lang), version, id)}
      aside={tocHeadings.length ? <Toc headings={tocHeadings} title={dict.onThisPage} /> : undefined}
    >
      <article className="docs-article">
        <header className="docs-article-header">
          <nav aria-label={dict.breadcrumb} className="docs-breadcrumb">
            <SiteLink href={`/${lang}/${version}/`}>{dict.docs}</SiteLink>
            <Icon name="chevron" size={12} />
            <SiteLink href={`/${lang}/${version}/#${section}`}>{dict.sections[section]}</SiteLink>
          </nav>
          <h1>{page.frontmatter.title}</h1>
          <p>{page.frontmatter.description}</p>
        </header>
        {tocHeadings.length ? (
          <details className="docs-inline-toc">
            <summary>
              <Icon name="list" size={17} /> {dict.onThisPage}
            </summary>
            <Toc headings={tocHeadings} title={dict.onThisPage} compact />
          </details>
        ) : null}
        <div className="prose docs-prose">{content}</div>
        <a href={editHref} target="_blank" rel="noreferrer" className="docs-edit">
          <Icon name="edit" size={15} />
          {dict.editPage}
          <Icon name="arrowUpRight" size={13} />
        </a>
        {previous || next ? (
          <nav className="docs-pagination" aria-label={dict.pageNavigation}>
            {previous ? (
              <SiteLink href={previous.url} rel="prev">
                <span>{dict.previous}</span>
                <strong>
                  <Icon name="arrowLeft" size={16} />
                  {previous.title}
                </strong>
              </SiteLink>
            ) : null}
            {next ? (
              <SiteLink href={next.url} rel="next">
                <span>{dict.next}</span>
                <strong>
                  {next.title}
                  <Icon name="arrowRight" size={16} />
                </strong>
              </SiteLink>
            ) : null}
          </nav>
        ) : null}
      </article>
    </DocShell>
  );
}
