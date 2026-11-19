/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { SiteLink } from "@/components/layout/SiteLink";
import { Icon } from "@/components/ui/Icon";
import { CURRENT_VERSION, pageUrl } from "@/lib/content-core.mjs";
import type { Lang } from "@/lib/i18n";
import { integrationLabels } from "@/lib/integration";
import { BRAND_MARK } from "@/lib/site";
import { ConnectorMark } from "./ConnectorMark";

// Orders 1–3 of AcceptanceBenchmark.GENERATED. Figures below are from the
// 2026-11-17 single-host M1 run at 56255e6 (MySQL → PostgreSQL).
const orders = [
  [1, "ORD-000000000000", "0.99"],
  [2, "ORD-000000000001", "1.00"],
  [3, "ORD-000000000002", "1.01"],
] as const;

const endpoints = {
  source: {
    name: "MySQL",
    table: "bench.orders",
    connector: "mysql",
    page: "plugins/source/mysql",
    amountType: "DECIMAL(14,2)",
  },
  sink: {
    name: "PostgreSQL",
    table: "public.orders",
    connector: "postgresql",
    page: "plugins/sink/postgres",
    amountType: "NUMERIC(14,2)",
  },
} as const;

type Endpoint = keyof typeof endpoints;
const stepIcons = ["layers", "workflow", "database"] as const;

function OrderTable({ lang, type }: { lang: Lang; type: Endpoint }) {
  const endpoint = endpoints[type];
  const labels = integrationLabels(lang).benchmark;
  return (
    <section className="integration-endpoint" data-endpoint={type} aria-label={labels[type]}>
      <header className="integration-endpoint-header">
        <SiteLink href={pageUrl(lang, CURRENT_VERSION, endpoint.page)}>
          <span className="connector-mark">
            <ConnectorMark connector={endpoint.connector} />
          </span>
          <span>
            <strong>{endpoint.name}</strong>
            <code>{endpoint.table}</code>
          </span>
        </SiteLink>
        <span className="integration-role">{labels[type]}</span>
      </header>
      <table className="integration-records">
        <caption className="sr-only">{`${endpoint.name} ${labels.sampleRows}`}</caption>
        <thead>
          <tr>
            <th scope="col">id</th>
            <th scope="col">order_no</th>
            <th scope="col">amount</th>
          </tr>
        </thead>
        <tbody>
          {orders.map(([id, number, amount]) => (
            <tr key={id} className={id === 2 ? "integration-record-selected" : undefined}>
              <td>{id}</td>
              <td>{number}</td>
              <td>{amount}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="integration-field">
        <span>{labels.fieldType}</span>
        <code>amount · {endpoint.amountType}</code>
      </p>
    </section>
  );
}

export function IntegrationCase({ lang }: { lang: Lang }) {
  const labels = integrationLabels(lang).benchmark;
  return (
    <figure className="integration-case" aria-labelledby="integration-title">
      <figcaption className="integration-case-heading">
        <strong id="integration-title">
          <Icon name="workflow" size={17} />
          {labels.title}
        </strong>
        <div>
          <span>MySQL → PostgreSQL</span>
          <span className="integration-status">
            <Icon name="check" size={14} />
            {labels.passed}
          </span>
        </div>
      </figcaption>
      <div className="integration-lane">
        <OrderTable lang={lang} type="source" />
        <span className="integration-rail" aria-hidden="true">
          <Icon name="arrowRight" size={14} />
        </span>
        <section className="integration-engine" aria-label="PipeForge">
          <header>
            <img src={BRAND_MARK} alt="" width={24} height={24} />
            <strong>PipeForge</strong>
          </header>
          <ol>
            {labels.steps.map(([title, description], index) => (
              <li key={title}>
                <Icon name={stepIcons[index] ?? "workflow"} size={16} />
                <span>
                  <strong>{title}</strong>
                  <small>{description}</small>
                </span>
              </li>
            ))}
          </ol>
        </section>
        <span className="integration-rail integration-rail-write" aria-hidden="true">
          <Icon name="arrowRight" size={14} />
        </span>
        <OrderTable lang={lang} type="sink" />
      </div>
      <div className="integration-case-footer">
        <dl>
          {labels.stats.map(([value, label]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
        <SiteLink
          href={pageUrl(lang, CURRENT_VERSION, "development/benchmarks")}
          aria-label={`${labels.environment} · ${labels.details}`}
        >
          <Icon name="monitor" size={15} />
          {labels.environment}
          <Icon name="arrowUpRight" size={13} />
        </SiteLink>
      </div>
    </figure>
  );
}
