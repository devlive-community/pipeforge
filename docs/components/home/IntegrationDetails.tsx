/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { SiteLink } from "@/components/layout/SiteLink";
import { Screenshot } from "@/components/mdx/Screenshot";
import { ButtonLink } from "@/components/ui/Button";
import { Icon, type IconName } from "@/components/ui/Icon";
import { CURRENT_VERSION, pageUrl } from "@/lib/content-core.mjs";
import type { Lang } from "@/lib/i18n";
import { integrationLabels } from "@/lib/integration";
import { ConnectorMark } from "./ConnectorMark";

const useCases = [
  { key: "migration", page: "development/benchmarks", icon: "workflow" },
  { key: "query", page: "plugins/source/mysql", icon: "database" },
  { key: "mapping", page: "plugins/transform/field-mapper", icon: "sliders" },
] as const;

const connectors = [
  { name: "MySQL", mark: "mysql", slug: "mysql" },
  { name: "PostgreSQL", mark: "postgresql", slug: "postgres" },
] as const;

const workflow = [
  { key: "author", page: "guides/job-editor", icon: "edit" },
  { key: "validate", page: "concepts/plan-and-explain", icon: "list" },
  { key: "run", page: "guides/runs", icon: "activity" },
] as const;

function DetailLink({
  href,
  icon,
  title,
  description,
}: {
  href: string;
  icon: IconName;
  title: string;
  description: string;
}) {
  return (
    <li>
      <SiteLink href={href}>
        <span className="integration-detail-icon">
          <Icon name={icon} size={17} />
        </span>
        <span>
          <strong>{title}</strong>
          <small>{description}</small>
        </span>
        <Icon name="arrowRight" size={15} />
      </SiteLink>
    </li>
  );
}

export function IntegrationDetails({ lang }: { lang: Lang }) {
  const { homepage: labels, benchmark } = integrationLabels(lang);
  return (
    <div className="integration-details">
      <div className="integration-details-grid">
        <section aria-labelledby="integration-use-cases-heading">
          <h2 id="integration-use-cases-heading">{labels.useCasesTitle}</h2>
          <ul className="integration-detail-list">
            {useCases.map(({ key, page, icon }) => {
              const [title, description] = labels.useCases[key];
              return (
                <DetailLink
                  key={key}
                  href={pageUrl(lang, CURRENT_VERSION, page)}
                  icon={icon}
                  title={title}
                  description={description}
                />
              );
            })}
          </ul>
        </section>
        <section aria-labelledby="integration-databases-heading">
          <h2 id="integration-databases-heading">{labels.connectorsTitle}</h2>
          <div className="integration-database-list">
            <p>{labels.connectorsText}</p>
            <ul>
              {connectors.map(({ name, mark, slug }) => (
                <li key={slug}>
                  <span className="connector-mark">
                    <ConnectorMark connector={mark} />
                  </span>
                  <strong>{name}</strong>
                  <div>
                    <SiteLink
                      href={pageUrl(lang, CURRENT_VERSION, `plugins/source/${slug}`)}
                      aria-label={`${name} ${benchmark.source}`}
                    >
                      Source
                    </SiteLink>
                    <SiteLink
                      href={pageUrl(lang, CURRENT_VERSION, `plugins/sink/${slug}`)}
                      aria-label={`${name} ${benchmark.sink}`}
                    >
                      Sink
                    </SiteLink>
                  </div>
                </li>
              ))}
            </ul>
            <SiteLink href={`/${lang}/${CURRENT_VERSION}/#connectors`} className="integration-link">
              {labels.allConnectors}
              <Icon name="arrowRight" size={15} />
            </SiteLink>
          </div>
        </section>
      </div>
      <section className="integration-workflow" aria-labelledby="integration-workflow-heading">
        <div>
          <h2 id="integration-workflow-heading">{labels.workflowTitle}</h2>
          <ol className="integration-detail-list">
            {workflow.map(({ key, page, icon }) => {
              const [title, description] = labels.workflow[key];
              return (
                <DetailLink
                  key={key}
                  href={pageUrl(lang, CURRENT_VERSION, page)}
                  icon={icon}
                  title={title}
                  description={description}
                />
              );
            })}
          </ol>
        </div>
        <Screenshot name="run-detail" lang={lang} alt={labels.screenshotAlt} />
      </section>
      <section className="integration-next" aria-labelledby="integration-next-heading">
        <div>
          <h2 id="integration-next-heading">{labels.startTitle}</h2>
          <p>{labels.startText}</p>
        </div>
        <div className="integration-actions">
          <ButtonLink href={pageUrl(lang, CURRENT_VERSION, "getting-started/quickstart")} variant="primary">
            {labels.startLocal}
            <Icon name="arrowRight" size={15} />
          </ButtonLink>
          <ButtonLink href={pageUrl(lang, CURRENT_VERSION, "getting-started/cluster-quickstart")}>
            {labels.startCluster}
            <Icon name="arrowUpRight" size={15} />
          </ButtonLink>
        </div>
      </section>
    </div>
  );
}
