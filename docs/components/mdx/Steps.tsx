/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { Children, isValidElement, type ReactNode } from "react";

/** A numbered, step-by-step procedure. Each child is a <Step title="...">. */
export function Steps({ children }: { children: ReactNode }) {
  const steps = Children.toArray(children).filter(isValidElement);
  return (
    <ol className="relative ml-3 list-none border-l border-border pl-0">
      {steps.map((step, index) => (
        <li key={index} className="relative pb-6 pl-8 last:pb-0">
          <span
            aria-hidden="true"
            className="absolute top-0 -left-3.5 flex size-7 items-center justify-center rounded-full border border-border bg-surface text-sm font-semibold text-fg"
          >
            {index + 1}
          </span>
          {step}
        </li>
      ))}
    </ol>
  );
}

export function Step({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="[&>*+*]:mt-3">
      <p className="font-semibold text-fg">{title}</p>
      {children}
    </div>
  );
}
