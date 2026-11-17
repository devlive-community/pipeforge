/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import createFetchClient from "openapi-fetch";
import type { components, paths } from "./schema";

export type { components, paths };

type Schemas = components["schemas"];
export type ErrorInfo = Schemas["ErrorInfo"];
export type VersionInfo = Schemas["VersionInfo"];
export type PluginList = Schemas["PluginList"];
export type ComponentInfo = Schemas["ComponentInfo"];
export type FunctionInfo = Schemas["FunctionInfo"];
export type ErrorCodeInfo = Schemas["ErrorCodeInfo"];
export type JobRequest = Schemas["JobRequest"];
export type ValidationResult = Schemas["ValidationResult"];
export type ExplainResult = Schemas["ExplainResult"];
export type EvaluationRequest = Schemas["EvaluationRequest"];
export type EvaluationResult = Schemas["EvaluationResult"];
export type SubmitRequest = Schemas["SubmitRequest"];
export type Run = Schemas["Run"];
export type RunDetail = Schemas["RunDetail"];
export type RunPage = Schemas["RunPage"];
export type RunState = NonNullable<Run["status"]>;
export type RunMetrics = Schemas["RunMetrics"];
export type MetricSample = Schemas["Sample"];
export type RunSplit = Schemas["Split"];
export type RunTask = Schemas["Task"];
export type LogChunk = Schemas["LogChunk"];
export type ClusterView = Schemas["ClusterView"];
export type PreviewRequest = Schemas["PreviewRequest"];
export type PreviewResult = Schemas["PreviewResult"];

/** Which part of a task's log to read. */
export interface LogQuery {
  vertex: string;
  subtask: number;
  attempt?: number;
  /** Where to start; the offset of the previous chunk to follow the log. */
  offset?: number;
  maxBytes?: number;
  contains?: string;
  level?: "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR";
}

/** Which runs to list: some states, one job, and the page. */
export interface RunQuery {
  status?: RunState[];
  job?: string;
  limit?: number;
  page?: string;
}

/** The languages of messages the server provides. */
export type Language = "en-US" | "zh-CN";

/** An error response of the API: the HTTP status and the structured error object. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly info: ErrorInfo,
  ) {
    super(info.message ?? `HTTP ${status}`);
    this.name = "ApiError";
  }

  /** The registered error code, e.g. PF-CONF-0006. */
  get code(): string {
    return this.info.code ?? "";
  }
}

export interface ClientOptions {
  /** Where the server is; empty for the same origin as the page. */
  baseUrl?: string;
  /** The language of messages, or a function returning it for every request. */
  language?: Language | (() => Language);
  /** A fetch implementation, for tests and non-browser runtimes. */
  fetch?: typeof globalThis.fetch;
}

/**
 * A client of the PipeForge API. Every method resolves to the response body and rejects with
 * {@link ApiError} when the server answers with an error.
 */
export function createClient(options: ClientOptions = {}) {
  const raw = createFetchClient<paths>({ baseUrl: options.baseUrl ?? "", fetch: options.fetch });
  const language = options.language;
  if (language) {
    raw.use({
      onRequest({ request }) {
        request.headers.set("Accept-Language", typeof language === "function" ? language() : language);
        return request;
      },
    });
  }

  async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
    const { data, error, response } = await call;
    if (!response.ok || data === undefined) {
      throw new ApiError(response.status, (error ?? { message: response.statusText }) as ErrorInfo);
    }
    return data;
  }

  return {
    /** The underlying typed fetch client, for endpoints without a helper. */
    raw,
    getVersion: () => unwrap(raw.GET("/api/v1/version")),
    listPlugins: () => unwrap(raw.GET("/api/v1/plugins")),
    getComponent: (type: "source" | "transform" | "sink", identifier: string, plugin?: string) =>
      unwrap(
        raw.GET("/api/v1/components/{type}/{identifier}", {
          params: { path: { type, identifier }, query: plugin ? { plugin } : {} },
        }),
      ),
    listFunctions: () => unwrap(raw.GET("/api/v1/functions")),
    listErrorCodes: () => unwrap(raw.GET("/api/v1/errors")),
    getJobSchema: () => unwrap(raw.GET("/api/v1/jobs/schema")),
    validateJob: (body: JobRequest) => unwrap(raw.POST("/api/v1/jobs/validate", { body })),
    previewJob: (body: PreviewRequest) => unwrap(raw.POST("/api/v1/jobs/preview", { body })),
    explainJob: (body: JobRequest) => unwrap(raw.POST("/api/v1/explain", { body })),
    evaluateExpression: (body: EvaluationRequest) => unwrap(raw.POST("/api/v1/expressions/evaluate", { body })),
    submitRun: (body: SubmitRequest) => unwrap(raw.POST("/api/v1/runs", { body })),
    listRuns: (query: RunQuery = {}) => unwrap(raw.GET("/api/v1/runs", { params: { query } })),
    getRun: (id: string) => unwrap(raw.GET("/api/v1/runs/{id}", { params: { path: { id } } })),
    getCluster: () => unwrap(raw.GET("/api/v1/cluster")),
    getRunMetrics: (id: string) => unwrap(raw.GET("/api/v1/runs/{id}/metrics", { params: { path: { id } } })),
    getRunMetricsHistory: (id: string, since?: string) =>
      unwrap(raw.GET("/api/v1/runs/{id}/metrics/history", { params: { path: { id }, query: since ? { since } : {} } })),
    getRunAnalysis: (id: string) => unwrap(raw.GET("/api/v1/runs/{id}/analysis", { params: { path: { id } } })),
    listRunSplits: (id: string) => unwrap(raw.GET("/api/v1/runs/{id}/splits", { params: { path: { id } } })),
    listRunTasks: (id: string) => unwrap(raw.GET("/api/v1/runs/{id}/tasks", { params: { path: { id } } })),
    readRunLog: (id: string, query: LogQuery) =>
      unwrap(raw.GET("/api/v1/runs/{id}/logs", { params: { path: { id }, query } })),
    cancelRun: (id: string, reason?: string) =>
      unwrap(raw.POST("/api/v1/runs/{id}/cancel", { params: { path: { id } }, body: reason ? { reason } : {} })),
  };
}

export type PipeForgeClient = ReturnType<typeof createClient>;
