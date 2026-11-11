/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Metadata } from "next";
import Link from "next/link";
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

  return (
    <DocShell
      lang={lang}
      sections={navigation(lang)}
      currentId={id}
      otherLangHref={pageUrl(otherLang(lang), version, id)}
      aside={<Toc headings={tocHeadings} title={dict.onThisPage} />}
    >
      <article className="mx-auto max-w-3xl">
        <p className="text-sm font-medium text-primary">
          {dict.sections[id.split("/")[0] as keyof typeof dict.sections]}
        </p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight text-fg">{page.frontmatter.title}</h1>
        <p className="mt-3 text-lg text-fg-muted">{page.frontmatter.description}</p>
        <div className="prose mt-8">{content}</div>
        <div className="mt-12 flex items-center justify-between gap-4 border-t border-border pt-6 text-sm">
          {previous ? (
            <Link href={previous.url} className="group flex items-center gap-2 text-fg-muted hover:text-fg">
              <Icon name="arrowLeft" className="transition-transform duration-fast group-hover:-translate-x-0.5" />
              <span>
                <span className="block text-xs text-fg-subtle">{dict.previous}</span>
                {previous.title}
              </span>
            </Link>
          ) : (
            <span />
          )}
          {next ? (
            <Link href={next.url} className="group flex items-center gap-2 text-right text-fg-muted hover:text-fg">
              <span>
                <span className="block text-xs text-fg-subtle">{dict.next}</span>
                {next.title}
              </span>
              <Icon name="arrowRight" className="transition-transform duration-fast group-hover:translate-x-0.5" />
            </Link>
          ) : null}
        </div>
        <p className="mt-6 text-sm">
          <a
            href={`${REPOSITORY_URL}/edit/main/docs/content/${page.file}`}
            target="_blank"
            rel="noreferrer"
            className="text-fg-muted underline underline-offset-2 hover:text-fg"
          >
            {dict.editPage}
          </a>
        </p>
      </article>
    </DocShell>
  );
}
