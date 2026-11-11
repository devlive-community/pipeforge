/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { ReactNode } from "react";
import { useErrorCodes, useFunctions, usePlugins, useVersion } from "@/api/queries";
import { useT } from "@/i18n";
import { Badge, Button, Card, DescriptionList, Empty, Icon, PageHeader, Skeleton, type IconName } from "@/ui";

function Stat({
  icon,
  label,
  value,
  note,
}: {
  icon: IconName;
  label: string;
  value: number | undefined;
  note?: ReactNode;
}) {
  return (
    <Card>
      <div className="flex items-center gap-2 text-sm text-fg-muted">
        <Icon name={icon} />
        {label}
      </div>
      <div className="mt-2 text-2xl font-semibold text-fg">
        {value === undefined ? <Skeleton className="h-8 w-16" /> : value}
      </div>
      {note ? <div className="mt-2">{note}</div> : null}
    </Card>
  );
}

/** The start page: the server the UI talks to and what is installed on it. */
export function OverviewPage() {
  const t = useT();
  const version = useVersion();
  const plugins = usePlugins();
  const functions = useFunctions();
  const errors = useErrorCodes();

  if (version.isError) {
    const retry = () => {
      void version.refetch();
      void plugins.refetch();
      void functions.refetch();
      void errors.refetch();
    };
    return (
      <>
        <PageHeader title={t("overview.title")} description={t("overview.description")} />
        <Card>
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
        </Card>
      </>
    );
  }

  const unavailable = plugins.data?.unavailable ?? [];
  return (
    <>
      <PageHeader title={t("overview.title")} description={t("overview.description")} />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Card
          title={
            <span className="flex items-center gap-2">
              <Icon name="server" />
              {t("overview.server")}
            </span>
          }
          actions={version.data ? <Badge tone="success">{t("overview.connected")}</Badge> : null}
        >
          {version.data ? (
            <DescriptionList
              items={[
                { label: t("overview.version"), value: version.data.version },
                { label: t("overview.connectorApi"), value: version.data.connectorApi },
                { label: t("overview.java"), value: version.data.java },
              ]}
            />
          ) : (
            <div className="space-y-2">
              <Skeleton className="h-4 w-40" />
              <Skeleton className="h-4 w-32" />
              <Skeleton className="h-4 w-36" />
            </div>
          )}
        </Card>
        <Stat
          icon="plug"
          label={t("overview.plugins")}
          value={plugins.data?.plugins?.length}
          note={
            plugins.data ? (
              unavailable.length > 0 ? (
                <Badge tone="warning">{t("overview.pluginsUnavailable", { count: unavailable.length })}</Badge>
              ) : (
                <Badge tone="success">{t("overview.pluginsAllLoaded")}</Badge>
              )
            ) : null
          }
        />
        <Stat icon="functions" label={t("overview.functions")} value={functions.data?.length} />
        <Stat
          icon="codes"
          label={t("overview.errorCodes")}
          value={errors.data?.length}
          note={errors.data ? <Badge>{t("overview.registered")}</Badge> : null}
        />
      </div>
      {unavailable.length > 0 ? (
        <Card title={t("overview.unavailableTitle")} className="mt-4">
          <ul className="divide-y divide-border text-sm">
            {unavailable.map((item) => (
              <li key={item.location} className="py-2">
                <div className="font-mono text-xs text-fg">{item.location}</div>
                <div className="mt-0.5 text-fg-muted">{item.reason}</div>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}
    </>
  );
}
