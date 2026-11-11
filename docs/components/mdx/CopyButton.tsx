/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { useEffect, useRef, useState } from "react";
import { cx } from "../ui/cx";
import { Icon } from "../ui/Icon";
import { IconButton } from "../ui/IconButton";

/** Copies `text`; the icon turns into a check mark with a small bounce for 1.5 seconds. */
export function CopyButton({ text, label, copiedLabel }: { text: string; label: string; copiedLabel: string }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => () => clearTimeout(timer.current), []);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      return;
    }
    setCopied(true);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), 1500);
  }

  return (
    <IconButton icon="copy" label={copied ? copiedLabel : label} size="sm" onClick={copy} data-copied={copied}>
      <span key={String(copied)} className={cx("inline-flex", copied && "animate-scale-in text-success")}>
        <Icon name={copied ? "check" : "copy"} size={15} />
      </span>
      <span role="status" aria-live="polite" className="sr-only">
        {copied ? copiedLabel : ""}
      </span>
    </IconButton>
  );
}
