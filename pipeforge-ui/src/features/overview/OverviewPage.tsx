/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";
import { Link } from "react-router";
import { useErrorCodes, useFunctions, usePlugins, useVersion } from "@/api/queries";
import { useT } from "@/i18n";
import {
  Badge,
  Button,
  buttonClass,
  Card,
  DescriptionList,
  Empty,
  Icon,
  PageHeader,
  LoadingLogo,
  type IconName,
} from "@/ui";
import { LoadingPanel } from "@/ui/LoadingPanel";

const FLOW = [
  { kind: "source", plugin: "fake" },
  { kind: "transform", plugin: "field-mapper" },
  { kind: "sink", plugin: "console" },
] as const;

function Resource({
  icon,
  label,
  value,
  pending,
  note,
  to,
}: {
  icon: IconName;
  label: string;
  value: number | undefined;
  pending: boolean;
  note?: ReactNode;
  to: string;
}) {
  const t = useT();
  return (
    <Link to={to} className="resource-card" aria-label={t("overview.open", { page: label })}>
      <span className="resource-icon">
        <Icon name={icon} size={20} />
      </span>
      <Icon name="arrowRight" size={17} className="resource-arrow" />
      <span className="resource-value">
        {pending ? (
          <span className="flex items-center" role="status">
            <LoadingLogo size={32} />
            <span className="sr-only">{t("app.loading")}</span>
          </span>
        ) : (
          (value ?? "—")
        )}
      </span>
      <span className="resource-label">{label}</span>
      {note ? <span className="mt-1">{note}</span> : null}
    </Link>
  );
}

export function OverviewPage() {
  const t = useT();
  const version = useVersion();
  const plugins = usePlugins();
  const functions = useFunctions();
  const errors = useErrorCodes();
  const unavailable = plugins.data?.unavailable ?? [];

  function retry() {
    void version.refetch();
    void plugins.refetch();
    void functions.refetch();
    void errors.refetch();
  }

  return (
    <div className="overview-page">
      <PageHeader title={t("overview.title")} description={t("overview.description")} />
      <div className="overview-top">
        <section className="workflow-card">
          <div>
            <span className="workflow-icon">
              <Icon name="topology" size={22} />
            </span>
            <h2>{t("overview.workflow")}</h2>
            <p>{t("overview.workflowHint")}</p>
            <Link to="/editor" className={buttonClass("primary", "lg", "mt-5")}>
              {t("overview.workflowAction")} <Icon name="arrowRight" size={17} />
            </Link>
          </div>
          <ol className="workflow-diagram">
            {FLOW.map(({ kind, plugin }) => (
              <li key={kind} data-kind={kind}>
                <span className="workflow-node-icon">
                  <Icon name={kind} />
                </span>
                <span>
                  <span className="workflow-stage">{t(`editor.kinds.${kind}`)}</span>
                  <code>{plugin}</code>
                </span>
              </li>
            ))}
          </ol>
        </section>
        <Card
          className="server-card"
          title={
            <span className="flex items-center gap-2">
              <Icon name="server" />
              {t("overview.server")}
            </span>
          }
          actions={version.data && !version.isError ? <Badge tone="success">{t("overview.connected")}</Badge> : null}
        >
          {version.isPending ? (
            <LoadingPanel className="min-h-24" />
          ) : version.isError ? (
            <div className="server-error-content">
              <Empty
                icon="alert"
                title={t("overview.errorTitle")}
                description={t("overview.errorDescription")}
                action={
                  <Button icon="refresh" loading={version.isFetching} onClick={retry}>
                    {t("overview.retry")}
                  </Button>
                }
              />
            </div>
          ) : (
            <DescriptionList
              items={[
                { label: t("overview.version"), value: version.data.version },
                { label: t("overview.connectorApi"), value: version.data.connectorApi },
                { label: t("overview.java"), value: version.data.java },
              ]}
            />
          )}
        </Card>
      </div>
      <section className="overview-resources">
        <h2>{t("overview.resources")}</h2>
        <div className="resource-grid">
          <Resource
            icon="plug"
            to="/plugins"
            label={t("overview.plugins")}
            value={plugins.data?.plugins?.length}
            pending={plugins.isPending}
            note={
              unavailable.length > 0 ? (
                <Badge tone="warning">{t("overview.pluginsUnavailable", { count: unavailable.length })}</Badge>
              ) : plugins.data ? (
                <Badge tone="success">{t("overview.pluginsAllLoaded")}</Badge>
              ) : null
            }
          />
          <Resource
            icon="functions"
            to="/functions"
            label={t("overview.functions")}
            value={functions.data?.length}
            pending={functions.isPending}
          />
          <Resource
            icon="codes"
            to="/errors"
            label={t("overview.errorCodes")}
            value={errors.data?.length}
            pending={errors.isPending}
            note={errors.data ? <Badge>{t("overview.registered")}</Badge> : null}
          />
        </div>
      </section>
      {unavailable.length > 0 ? (
        <Card title={t("overview.unavailableTitle")} className="mt-5">
          <ul className="divide-y divide-border text-sm">
            {unavailable.map((item) => (
              <li key={item.location} className="py-2">
                <div className="font-mono text-xs break-all text-fg">{item.location}</div>
                <div className="mt-0.5 text-fg-muted">{item.reason}</div>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}
    </div>
  );
}
