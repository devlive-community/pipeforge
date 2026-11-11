/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { evaluate } from "@mdx-js/mdx";
import Link from "next/link";
import type { ComponentProps, ReactElement, ReactNode } from "react";
import * as runtime from "react/jsx-runtime";
import remarkGfm from "remark-gfm";
import { Callout } from "@/components/mdx/Callout";
import { CodeBlock } from "@/components/mdx/CodeBlock";
import { ErrorList } from "@/components/mdx/ErrorList";
import { Step, Steps } from "@/components/mdx/Steps";
import { Tab, Tabs } from "@/components/mdx/Tabs";
import { CURRENT_VERSION, pageUrl, resolveLink } from "./content-core.mjs";
import type { Lang } from "./i18n";
import { rehypeHeadingIds, remarkCodeMeta } from "./mdx-plugins.mjs";

interface Context {
  lang: Lang;
  pageId: string;
}

function DocLink({ href = "", children, lang, pageId, ...rest }: ComponentProps<"a"> & Context) {
  const target = resolveLink(pageId, href);
  if (target.kind === "external") {
    return (
      <a href={href} target="_blank" rel="noreferrer" {...rest}>
        {children}
      </a>
    );
  }
  if (target.kind === "page") {
    const url = pageUrl(lang, CURRENT_VERSION, target.id) + (target.anchor ? `#${target.anchor}` : "");
    return <Link href={target.id === pageId && target.anchor ? `#${target.anchor}` : url}>{children}</Link>;
  }
  return <a {...rest}>{children}</a>;
}

function Pre({ children, lang }: { children?: ReactNode; lang: Lang }) {
  const code = children as ReactElement<{ className?: string; children?: string; "data-meta"?: string }>;
  const language = /language-([\w-]+)/.exec(code?.props?.className ?? "")?.[1] ?? "text";
  const source = String(code?.props?.children ?? "").replace(/\n$/, "");
  return <CodeBlock code={source} language={language} meta={code?.props?.["data-meta"]} lang={lang} />;
}

function Heading({ as: Tag, id, children }: { as: "h2" | "h3"; id?: string; children?: ReactNode }) {
  return (
    <Tag id={id} className="group scroll-mt-24">
      {children}
      {id ? (
        <a
          href={`#${id}`}
          aria-hidden="true"
          tabIndex={-1}
          className="ml-2 text-fg-subtle no-underline opacity-0 transition-opacity duration-fast group-hover:opacity-100"
        >
          #
        </a>
      ) : null}
    </Tag>
  );
}

function components(context: Context) {
  return {
    a: (props: ComponentProps<"a">) => <DocLink {...props} {...context} />,
    pre: (props: { children?: ReactNode }) => <Pre {...props} lang={context.lang} />,
    h2: (props: ComponentProps<"h2">) => <Heading as="h2" {...props} />,
    h3: (props: ComponentProps<"h3">) => <Heading as="h3" {...props} />,
    table: (props: ComponentProps<"table">) => (
      <div className="overflow-x-auto">
        <table {...props} />
      </div>
    ),
    Callout: (props: ComponentProps<typeof Callout>) => <Callout {...props} lang={context.lang} />,
    ErrorList: () => <ErrorList lang={context.lang} />,
    Tabs,
    Tab,
    Steps,
    Step,
  };
}

/** Compiles and renders an MDX page body on the server (at build time). */
export async function renderMdx(body: string, context: Context) {
  const { default: Content } = await evaluate(body, {
    ...runtime,
    remarkPlugins: [remarkGfm, remarkCodeMeta],
    rehypePlugins: [rehypeHeadingIds],
  });
  return <Content components={components(context)} />;
}
