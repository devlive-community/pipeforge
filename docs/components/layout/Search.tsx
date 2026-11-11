/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";
import type { Lang } from "@/lib/i18n";
import { buildSearch, type SearchResult } from "@/lib/search";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { Icon } from "../ui/Icon";
import { TextInput } from "../ui/TextInput";

interface Labels {
  search: string;
  searchPlaceholder: string;
  searchEmpty: string;
  searchHint: string;
}

type Searcher = (query: string) => SearchResult[];

/**
 * Full-text search over the build-time index. Opens with the button or Ctrl/⌘ K, scales in,
 * lists results one after another; arrow keys move a highlight block, Enter opens, Esc
 * closes and returns focus to the trigger.
 */
export function Search({ lang, labels }: { lang: Lang; labels: Labels }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[]>([]);
  const [active, setActive] = useState(0);
  const [highlight, setHighlight] = useState<{ top: number; height: number } | null>(null);
  const searcher = useRef<Searcher | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLUListElement>(null);

  const close = useCallback(() => {
    setOpen(false);
    setQuery("");
    setResults([]);
    trigger.current?.focus();
  }, []);

  useEffect(() => {
    function onKey(event: globalThis.KeyboardEvent) {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setOpen(true);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (!open) return;
    input.current?.focus();
    if (searcher.current) return;
    const base = process.env.NEXT_PUBLIC_BASE_PATH ?? "";
    fetch(`${base}/search/${lang}.json`)
      .then((response) => response.json())
      .then((documents) => {
        searcher.current = buildSearch(documents);
        setQuery((q) => q);
      })
      .catch(() => {
        searcher.current = () => [];
      });
  }, [open, lang]);

  useEffect(() => {
    setResults(query.trim() && searcher.current ? searcher.current(query) : []);
    setActive(0);
  }, [query, open]);

  useLayoutEffect(() => {
    const item = list.current?.children[active] as HTMLElement | undefined;
    setHighlight(item ? { top: item.offsetTop, height: item.offsetHeight } : null);
  }, [active, results]);

  function go(result: SearchResult | undefined) {
    if (!result) return;
    close();
    router.push(result.url);
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      close();
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      setActive((a) => Math.min(a + 1, Math.max(results.length - 1, 0)));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (event.key === "Enter") {
      event.preventDefault();
      go(results[active]);
    } else if (event.key === "Tab") {
      // Keep focus inside the panel: the input is its only focusable element.
      event.preventDefault();
      input.current?.focus();
    }
  }

  return (
    <>
      <Button
        ref={trigger}
        variant="secondary"
        size="sm"
        onClick={() => setOpen(true)}
        aria-haspopup="dialog"
        className="w-9 px-0 text-fg-muted sm:w-48 sm:justify-start sm:px-2.5"
      >
        <Icon name="search" size={15} />
        <span className="hidden sm:inline">{labels.search}</span>
        <kbd className="ml-auto hidden rounded-control border border-border px-1.5 font-sans text-xs sm:inline">⌘K</kbd>
      </Button>
      {open ? (
        <div
          className="fixed inset-0 z-50 flex items-start justify-center bg-overlay px-4 pt-[12vh] animate-fade-in"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) close();
          }}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-label={labels.search}
            onKeyDown={onKeyDown}
            className="w-full max-w-xl overflow-hidden rounded-card border border-border bg-surface shadow-modal animate-scale-in"
          >
            <div className="flex items-center gap-3 border-b border-border px-4">
              <Icon name="search" size={18} className="text-fg-subtle" />
              <TextInput
                ref={input}
                label={labels.searchPlaceholder}
                placeholder={labels.searchPlaceholder}
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                role="combobox"
                aria-expanded={results.length > 0}
                aria-controls="search-results"
                aria-activedescendant={results[active] ? `search-result-${active}` : undefined}
                autoComplete="off"
                spellCheck={false}
              />
            </div>
            <div className="relative max-h-[60vh] overflow-y-auto p-2">
              {highlight && results.length > 0 ? (
                <span
                  aria-hidden="true"
                  className="pointer-events-none absolute right-2 left-2 rounded-control bg-primary-soft transition-transform duration-fast ease-out"
                  style={{ height: highlight.height, transform: `translateY(${highlight.top}px)` }}
                />
              ) : null}
              <ul ref={list} id="search-results" role="listbox" aria-label={labels.search} className="relative">
                {results.map((result, index) => (
                  <li
                    key={result.url}
                    id={`search-result-${index}`}
                    role="option"
                    aria-selected={index === active}
                    onMouseEnter={() => setActive(index)}
                    onClick={() => go(result)}
                    className="animate-fade-up cursor-pointer rounded-control px-3 py-2"
                    style={{ animationDelay: `${Math.min(index, 8) * 30}ms` }}
                  >
                    <p className={cx("text-sm font-medium", index === active ? "text-primary" : "text-fg")}>
                      {result.title}
                    </p>
                    {result.excerpt ? (
                      <p className="mt-0.5 line-clamp-1 text-xs text-fg-muted">{result.excerpt}</p>
                    ) : null}
                  </li>
                ))}
              </ul>
              {query.trim() && results.length === 0 ? (
                <p className="px-3 py-6 text-center text-sm text-fg-muted">{labels.searchEmpty}</p>
              ) : null}
            </div>
            <p className="border-t border-border px-4 py-2 text-xs text-fg-subtle">↑ ↓ {labels.searchHint}</p>
          </div>
        </div>
      ) : null}
    </>
  );
}
