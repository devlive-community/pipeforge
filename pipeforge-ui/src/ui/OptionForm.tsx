/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";
import { Badge } from "./Badge";

/** One option of a component, as GET /api/v1/components describes it. */
export interface OptionField {
  key: string;
  type: string;
  requirement: "required" | "one-of" | "conditional" | "optional" | string;
  defaultValue?: unknown;
  description?: string;
  min?: number;
  max?: number;
  values?: string[];
  sensitive?: boolean;
  deprecatedKeys?: string[];
  since?: string;
}

export interface OptionFormLabels {
  requirement: Record<string, string>;
  defaultValue: string;
  range: string;
  values: string;
  sensitive: string;
  deprecated: string;
  since: string;
}

function format(value: unknown): string {
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

/**
 * The options of a component as a form, built from its option rule. This version shows them
 * read-only, as documentation: key, type, requirement, default, range, choices and description.
 */
export function OptionForm({
  options,
  labels,
  empty,
}: {
  options: OptionField[];
  labels: OptionFormLabels;
  empty: ReactNode;
}) {
  if (options.length === 0) return <p className="text-sm text-fg-muted">{empty}</p>;
  return (
    <ul className="divide-y divide-border rounded-card border border-border">
      {options.map((option) => (
        <li key={option.key} className="px-3 py-2.5">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono text-sm font-medium text-fg">{option.key}</span>
            <span className="font-mono text-xs text-fg-muted">{option.type}</span>
            <Badge
              tone={
                option.requirement === "required" ? "danger" : option.requirement === "optional" ? "neutral" : "warning"
              }
            >
              {labels.requirement[option.requirement] ?? option.requirement}
            </Badge>
            {option.sensitive ? <Badge tone="info">{labels.sensitive}</Badge> : null}
          </div>
          {option.description ? <p className="mt-1 text-sm text-fg">{option.description}</p> : null}
          <dl className="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-fg-muted">
            {option.defaultValue !== undefined && option.defaultValue !== null ? (
              <div>
                <dt className="inline">{labels.defaultValue}: </dt>
                <dd className="inline font-mono text-fg">{format(option.defaultValue)}</dd>
              </div>
            ) : null}
            {option.min !== undefined || option.max !== undefined ? (
              <div>
                <dt className="inline">{labels.range}: </dt>
                <dd className="inline font-mono text-fg">
                  {option.min ?? "…"} – {option.max ?? "…"}
                </dd>
              </div>
            ) : null}
            {option.values && option.values.length > 0 ? (
              <div>
                <dt className="inline">{labels.values}: </dt>
                <dd className="inline font-mono text-fg">{option.values.join(", ")}</dd>
              </div>
            ) : null}
            {option.deprecatedKeys && option.deprecatedKeys.length > 0 ? (
              <div>
                <dt className="inline">{labels.deprecated}: </dt>
                <dd className="inline font-mono text-fg">{option.deprecatedKeys.join(", ")}</dd>
              </div>
            ) : null}
            {option.since ? (
              <div>
                <dt className="inline">{labels.since}: </dt>
                <dd className="inline text-fg">{option.since}</dd>
              </div>
            ) : null}
          </dl>
        </li>
      ))}
    </ul>
  );
}
