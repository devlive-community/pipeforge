/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { CSSProperties } from "react";
import { highlightLines, parseMeta } from "@/lib/highlight";
import { t, type Lang } from "@/lib/i18n";
import { cx } from "../ui/cx";
import { CopyButton } from "./CopyButton";

function tokenStyle(color: string | undefined, fontStyle: number | undefined): CSSProperties {
  // Colors are Shiki css-variables (var(--shiki-token-*)), mapped to the design tokens in index.css.
  const style: CSSProperties = {};
  if (color) style.color = color;
  if (fontStyle && fontStyle & 1) style.fontStyle = "italic";
  if (fontStyle && fontStyle & 2) style.fontWeight = 600;
  return style;
}

/** Highlighted code with optional file name, line numbers, highlighted lines and a copy button. */
export async function CodeBlock({
  code,
  language = "text",
  meta,
  lang = "en-US",
}: {
  code: string;
  language?: string;
  meta?: string;
  lang?: Lang;
}) {
  const options = parseMeta(meta);
  const lines = await highlightLines(code, language);
  const dict = t(lang);
  return (
    <figure className="group relative overflow-hidden rounded-popover border border-border bg-code-bg">
      {options.title ? (
        <figcaption className="border-b border-border px-4 py-2 font-mono text-xs text-fg-muted">
          {options.title}
        </figcaption>
      ) : null}
      <div className="absolute top-1.5 right-1.5 opacity-70 transition-opacity duration-fast group-hover:opacity-100">
        <CopyButton text={code} label={dict.copy} copiedLabel={dict.copied} />
      </div>
      <pre className="overflow-x-auto py-3 text-[0.85rem] leading-6 text-code-fg" data-language={language}>
        <code className="grid min-w-max">
          {lines.map((line, index) => (
            <span
              key={index}
              data-line={index + 1}
              className={cx("px-4", options.highlight.has(index + 1) && "bg-primary-soft")}
            >
              {options.lineNumbers ? (
                <span className="mr-4 inline-block w-6 text-right text-fg-subtle select-none">{index + 1}</span>
              ) : null}
              {line.length === 0
                ? " "
                : line.map((token, i) => (
                    <span key={i} style={tokenStyle(token.color, token.fontStyle)}>
                      {token.content}
                    </span>
                  ))}
            </span>
          ))}
        </code>
      </pre>
    </figure>
  );
}
