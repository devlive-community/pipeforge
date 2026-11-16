/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { useLinkStatus } from "next/link";
import { createPortal } from "react-dom";
import { LoadingIndicator } from "./LoadingIndicator";

export function NavigationLoading() {
  const { pending } = useLinkStatus();
  if (!pending) return null;

  return createPortal(
    <div className="page-loading rounded-card border border-border bg-surface px-4 py-3 shadow-popover">
      <LoadingIndicator
        size={40}
        label={
          <>
            <span lang="en-US" className="page-loading-en">
              Loading page…
            </span>
            <span lang="zh-CN" className="page-loading-zh">
              正在加载页面…
            </span>
          </>
        }
      />
    </div>,
    document.body,
  );
}
