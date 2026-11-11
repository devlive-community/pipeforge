/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { apiReference, type ApiOperation, type ApiSchema } from "@/lib/generated";
import type { Lang } from "@/lib/i18n";

const LABELS = {
  "en-US": {
    parameters: "Parameters",
    body: "Request body",
    responses: "Responses",
    schemas: "Schemas",
    required: "required",
    none: "none",
    statuses: {} as Record<string, string>,
  },
  "zh-CN": {
    parameters: "参数",
    body: "请求体",
    responses: "响应",
    schemas: "数据结构",
    required: "必填",
    none: "无",
    statuses: {
      "400": "请求不合法（PF-CONF-0007）",
      "422": "作业、表达式或插件选项不合法",
      "500": "内部错误（PF-SYS-0001）",
    } as Record<string, string>,
  },
} as const;

/** Links schema names inside a type such as `ExplainDocument` or `ErrorInfo[]` to their anchors. */
function TypeText({ type, schemas }: { type: string; schemas: Set<string> }) {
  const parts = type.split(/([A-Za-z]+)/);
  return (
    <code className="font-mono text-xs">
      {parts.map((part, i) =>
        schemas.has(part) ? (
          <a key={i} href={`#schema-${part.toLowerCase()}`} className="text-primary">
            {part}
          </a>
        ) : (
          <span key={i}>{part}</span>
        ),
      )}
    </code>
  );
}

/** The description of a response; the standard error statuses are translated. */
function description(status: string, text: string, translated: Record<string, string>): string {
  const shown = translated[status] ?? text;
  return shown && shown !== "OK" ? ` — ${shown}` : "";
}

function Operation({ op, lang, schemas }: { op: ApiOperation; lang: Lang; schemas: Set<string> }) {
  const labels = LABELS[lang];
  return (
    <section
      id={op.id.toLowerCase()}
      className="scroll-mt-24 rounded-card border border-border p-4 target:border-primary"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="rounded-control bg-primary-soft px-1.5 py-0.5 font-mono text-xs font-semibold text-primary">
          {op.method}
        </span>
        <a href={`#${op.id.toLowerCase()}`} className="font-mono text-sm font-semibold text-fg">
          {op.path}
        </a>
        <span className="text-sm text-fg-muted">{op.summary[lang]}</span>
      </div>
      <p className="mt-2 text-sm text-fg">{op.description[lang]}</p>
      <dl className="mt-3 space-y-2 text-sm">
        <div>
          <dt className="font-medium text-fg">{labels.parameters}</dt>
          <dd className="text-fg-muted">
            {op.parameters.length === 0
              ? labels.none
              : op.parameters.map((p) => (
                  <div key={p.name}>
                    <code className="font-mono text-xs text-fg">{p.name}</code> ({p.in}
                    {p.required ? `, ${labels.required}` : ""}) <TypeText type={p.type} schemas={schemas} />
                    {p.description ? ` — ${p.description}` : ""}
                  </div>
                ))}
          </dd>
        </div>
        <div>
          <dt className="font-medium text-fg">{labels.body}</dt>
          <dd className="text-fg-muted">
            {op.requestBody ? <TypeText type={op.requestBody} schemas={schemas} /> : labels.none}
          </dd>
        </div>
        <div>
          <dt className="font-medium text-fg">{labels.responses}</dt>
          <dd className="text-fg-muted">
            {op.responses.map((r) => (
              <div key={r.status}>
                <code className="font-mono text-xs text-fg">{r.status}</code>{" "}
                {r.type ? <TypeText type={r.type} schemas={schemas} /> : null}
                {description(r.status, r.description, labels.statuses)}
              </div>
            ))}
          </dd>
        </div>
      </dl>
    </section>
  );
}

function Schema({ schema, lang, schemas }: { schema: ApiSchema; lang: Lang; schemas: Set<string> }) {
  return (
    <section
      id={`schema-${schema.name.toLowerCase()}`}
      className="scroll-mt-24 rounded-card border border-border p-4 target:border-primary"
    >
      <h4 className="font-mono text-sm font-semibold text-fg">{schema.name}</h4>
      <table className="mt-2 w-full text-sm">
        <tbody>
          {schema.fields.map((f) => (
            <tr key={f.name} className="border-t border-border">
              <td className="py-1 pr-3 font-mono text-xs text-fg">{f.name}</td>
              <td className="py-1 pr-3 text-fg-muted">
                <TypeText type={f.type} schemas={schemas} />
              </td>
              <td className="py-1 text-xs text-fg-subtle">{f.required ? LABELS[lang].required : ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/** The REST API generated from the server's OpenAPI document: operations, then schemas. */
export function ApiList({ lang = "en-US" }: { lang?: Lang }) {
  const { operations, schemas } = apiReference();
  const names = new Set(schemas.map((s) => s.name));
  return (
    <div className="not-prose space-y-4">
      {operations.map((op) => (
        <Operation key={op.id} op={op} lang={lang} schemas={names} />
      ))}
      <h3 className="pt-4 text-lg font-semibold text-fg">{LABELS[lang].schemas}</h3>
      {schemas.map((s) => (
        <Schema key={s.name} schema={s} lang={lang} schemas={names} />
      ))}
    </div>
  );
}
