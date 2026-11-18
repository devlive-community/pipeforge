/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useEffect, useState, type ReactNode } from "react";
import { Badge } from "./Badge";
import { Button } from "./Button";
import { DropdownMenu } from "./DropdownMenu";
import { Input } from "./Input";
import { SegmentedControl } from "./SegmentedControl";
import { cx } from "./cx";

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
  /** The labels of the edit mode; needed with `onChange`. */
  editing?: OptionEditLabels;
}

export interface OptionEditLabels {
  /** The value field of an option, e.g. "Value of {key}"; `{key}` is replaced. */
  field: string;
  /** The choice that removes the option so that its default applies. */
  unset: string;
  /** Shown for lists and maps, which are edited in the text. */
  editInText: string;
  notANumber: string;
  notAnInteger: string;
  /** Out of range, with `{min}` and `{max}`. */
  outOfRange: string;
}

const VARIABLE = /^\$\{[^}]+\}$/;

/**
 * Turns what was typed for an option into its value: nothing removes the option, a variable
 * (`${...}`) stays text, numbers of integer and double options become numbers. Returns an error
 * key when the text is no value of the option.
 */
export function parseOptionValue(
  option: Pick<OptionField, "type" | "min" | "max">,
  text: string,
): { value: unknown } | { error: "notANumber" | "notAnInteger" | "outOfRange" } {
  const trimmed = text.trim();
  if (trimmed === "") return { value: undefined };
  if (VARIABLE.test(trimmed)) return { value: trimmed };
  if (option.type === "int" || option.type === "long" || option.type === "double") {
    const number = Number(trimmed);
    if (!Number.isFinite(number)) return { error: "notANumber" };
    if (option.type !== "double" && !Number.isInteger(number)) return { error: "notAnInteger" };
    if ((option.min !== undefined && number < option.min) || (option.max !== undefined && number > option.max)) {
      return { error: "outOfRange" };
    }
    return { value: number };
  }
  return { value: text };
}

/** A typed value of an option, written when Enter is pressed or the field is left. */
function TextValue({
  option,
  value,
  labels,
  onChange,
}: {
  option: OptionField;
  value: unknown;
  labels: OptionEditLabels;
  onChange: (value: unknown) => void;
}) {
  const shown = value === undefined || value === null ? "" : String(value);
  const [text, setText] = useState(shown);
  const [error, setError] = useState<string | undefined>();
  // a change of the text elsewhere shows here
  useEffect(() => {
    setText(shown);
    setError(undefined);
  }, [shown]);
  const commit = () => {
    if (text === shown) return;
    const parsed = parseOptionValue(option, text);
    if ("error" in parsed) {
      setError(
        labels[parsed.error].replace("{min}", String(option.min ?? "…")).replace("{max}", String(option.max ?? "…")),
      );
      return;
    }
    setError(undefined);
    onChange(parsed.value);
  };
  return (
    <Input
      label={labels.field.replace("{key}", option.key)}
      hideLabel
      type={option.sensitive && !VARIABLE.test(text) ? "password" : "text"}
      inputMode={option.type === "int" || option.type === "long" || option.type === "double" ? "decimal" : undefined}
      placeholder={option.defaultValue !== undefined && option.defaultValue !== null ? format(option.defaultValue) : ""}
      value={text}
      error={error}
      onChange={(e) => setText(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === "Enter") commit();
      }}
      className="mt-2 max-w-md font-mono"
    />
  );
}

/** The control that edits one option, by its type. */
function OptionValue({
  option,
  value,
  labels,
  onChange,
}: {
  option: OptionField;
  value: unknown;
  labels: OptionEditLabels;
  onChange: (value: unknown) => void;
}) {
  const field = labels.field.replace("{key}", option.key);
  if (option.type === "boolean") {
    const current = value === true || value === "true" ? "true" : value === false || value === "false" ? "false" : "";
    return (
      <div className="mt-2">
        <SegmentedControl
          label={field}
          value={current}
          onChange={(id) => onChange(id === "" ? undefined : id === "true")}
          segments={[
            { id: "", label: labels.unset },
            { id: "true", label: "true" },
            { id: "false", label: "false" },
          ]}
        />
      </div>
    );
  }
  if (option.type === "enum" && option.values && option.values.length > 0) {
    const current = value === undefined || value === null ? "" : String(value);
    return (
      <div className="mt-2">
        <DropdownMenu
          label={field}
          align="start"
          value={current}
          onSelect={(id) => onChange(id === "" ? undefined : id)}
          items={[{ id: "", label: labels.unset }, ...option.values.map((v) => ({ id: v, label: v }))]}
          trigger={(props) => (
            <Button {...props} size="sm" icon="chevronDown" aria-label={field}>
              <span className="font-mono">{current || labels.unset}</span>
            </Button>
          )}
        />
      </div>
    );
  }
  if (option.type === "list" || option.type === "map" || option.type === "object_list") {
    return (
      <p className="mt-2 text-xs text-fg-muted">
        {value !== undefined ? <span className="mr-2 font-mono wrap-anywhere text-fg">{format(value)}</span> : null}
        {labels.editInText}
      </p>
    );
  }
  return <TextValue option={option} value={value} labels={labels} onChange={onChange} />;
}

function format(value: unknown): string {
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

/**
 * The options of a component from its option rule. Read-only by default; with `onChange` each
 * option gets a control for its type, showing `values` (what the job sets) and reporting changes,
 * `undefined` to remove an option so that its default applies.
 */
export function OptionForm({
  options,
  labels,
  empty,
  values,
  onChange,
  className,
  showDetails = true,
}: {
  options: OptionField[];
  labels: OptionFormLabels;
  empty: ReactNode;
  values?: Record<string, unknown>;
  onChange?: (key: string, value: unknown) => void;
  className?: string;
  showDetails?: boolean;
}) {
  if (options.length === 0) return <p className="text-sm text-fg-muted">{empty}</p>;
  return (
    <ul className={cx("divide-y divide-border rounded-card border border-border", className)}>
      {options.map((option) => (
        <li key={option.key} className="px-3 py-2.5">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono text-sm font-medium text-fg">{option.key}</span>
            <span className="font-mono text-xs text-fg-muted">{option.type}</span>
            {showDetails || option.requirement !== "optional" ? (
              <Badge
                tone={
                  option.requirement === "required"
                    ? "danger"
                    : option.requirement === "optional"
                      ? "neutral"
                      : "warning"
                }
              >
                {labels.requirement[option.requirement] ?? option.requirement}
              </Badge>
            ) : null}
            {option.sensitive ? <Badge tone="info">{labels.sensitive}</Badge> : null}
          </div>
          {showDetails ? (
            <>
              {option.description ? <p className="mt-1 text-sm text-fg">{option.description}</p> : null}
              <dl className="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-fg-muted">
                {option.defaultValue !== undefined && option.defaultValue !== null ? (
                  <div>
                    <dt className="inline">{labels.defaultValue}: </dt>
                    <dd className="inline font-mono text-fg">{format(option.defaultValue)}</dd>
                  </div>
                ) : null}
                {option.min != null || option.max != null ? (
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
            </>
          ) : null}
          {onChange && labels.editing ? (
            <OptionValue
              option={option}
              value={values?.[option.key]}
              labels={labels.editing}
              onChange={(value) => onChange(option.key, value)}
            />
          ) : null}
        </li>
      ))}
    </ul>
  );
}
