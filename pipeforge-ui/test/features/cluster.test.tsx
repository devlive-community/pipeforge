/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { screen, within } from "@testing-library/react";
import { slotJobs } from "@/features/cluster/ClusterPage";
import { usePreferences } from "@/state/preferences";
import { fakeServer, renderApp } from "../render";

const WORKER = {
  id: "worker-1",
  rpcAddress: "10.0.0.11:6124",
  dataAddress: "10.0.0.11:6125",
  resourceGroup: "default",
  labels: { zone: "a", disk: "ssd" },
  cpuCores: 8,
  taskHeapBytes: 4 * 1024 ** 3,
  managedBytes: 1024 ** 3,
  networkBytes: 128 * 1024 ** 2,
  tasksRunning: 3,
  sinceHeartbeatMs: 1200,
  version: "0.1.0",
  slots: [
    { index: 0, allocated: true, job: "orders-daily" },
    { index: 1, allocated: true, job: "orders-daily" },
    { index: 2, allocated: true, job: "users-sync" },
    { index: 3, allocated: false },
  ],
};

const CLUSTER = {
  master: { address: "10.0.0.10:6123", version: "0.1.0", protocol: "1.0", startedAt: "2026-11-17T07:00:00Z" },
  slots: 8,
  slotsUsed: 3,
  usage: 0.375,
  workers: [
    { ...WORKER, id: "worker-2", labels: {}, sinceHeartbeatMs: 21_000, slots: [{ index: 0, allocated: false }] },
    WORKER,
  ],
};

describe("ClusterPage", () => {
  beforeEach(() => usePreferences.setState({ language: "en-US", theme: "system" }));

  it("shows the master, the slots and each worker", async () => {
    fakeServer({ "/api/v1/cluster": CLUSTER });
    renderApp("/cluster");
    expect(await screen.findByText("10.0.0.10:6123")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Cluster" })).toBeInTheDocument();
    expect(screen.getByText("38%")).toBeInTheDocument();
    expect(screen.getByText("3 of 8 in use")).toBeInTheDocument();
    expect(screen.getByText("2 connected")).toBeInTheDocument();

    const first = screen.getByRole("heading", { level: 2, name: "worker-1" }).closest("section") as HTMLElement;
    expect(within(first).getByRole("img", { name: "Slots of worker-1: 3 of 4 in use" })).toBeInTheDocument();
    expect(within(first).getByText("orders-daily · 2 slot(s)")).toBeInTheDocument();
    expect(within(first).getByText("zone=a")).toBeInTheDocument();
    expect(within(first).getByText("4 GiB")).toBeInTheDocument();
    expect(within(first).getByText("1 s ago")).toBeInTheDocument();
    expect(within(first).queryByText("Late")).not.toBeInTheDocument();
  });

  it("marks a worker whose heartbeat is late", async () => {
    fakeServer({ "/api/v1/cluster": CLUSTER });
    renderApp("/cluster");
    const second = (await screen.findByRole("heading", { level: 2, name: "worker-2" })).closest(
      "section",
    ) as HTMLElement;
    expect(within(second).getByText("Late")).toBeInTheDocument();
    expect(within(second).getByText("21 s ago")).toHaveClass("text-warning");
    expect(within(second).getByText("No labels")).toBeInTheDocument();
  });

  it("explains a server without a master", async () => {
    fakeServer({
      "/api/v1/cluster": [503, { code: "PF-CLUS-0015", message: "The server has no master to run jobs on" }],
    });
    renderApp("/cluster");
    expect(await screen.findByText("The server has no master")).toBeInTheDocument();
    expect(screen.getByText(/pipeforge\.master\.address/)).toBeInTheDocument();
  });

  it("explains an unreachable master", async () => {
    fakeServer({
      "/api/v1/cluster": [503, { code: "PF-CLUS-0008", message: "10.0.0.10:6123 cannot be reached: refused" }],
    });
    renderApp("/cluster");
    expect(await screen.findByText("The master cannot be reached")).toBeInTheDocument();
    expect(screen.getByText("10.0.0.10:6123 cannot be reached: refused")).toBeInTheDocument();
  });

  it("explains a cluster without workers, in Chinese", async () => {
    usePreferences.setState({ language: "zh-CN" });
    fakeServer({ "/api/v1/cluster": { ...CLUSTER, workers: [], slots: 0, slotsUsed: 0, usage: 0 } });
    renderApp("/cluster");
    expect(await screen.findByText("没有已连接的 Worker")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "集群", current: "page" })).toBeInTheDocument();
  });

  it("counts the slots each job holds", () => {
    expect(slotJobs(WORKER)).toEqual([
      { job: "orders-daily", count: 2 },
      { job: "users-sync", count: 1 },
    ]);
  });
});
