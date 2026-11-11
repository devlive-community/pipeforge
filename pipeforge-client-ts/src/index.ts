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
    validateJob: (body: JobRequest) => unwrap(raw.POST("/api/v1/jobs/validate", { body })),
    explainJob: (body: JobRequest) => unwrap(raw.POST("/api/v1/explain", { body })),
    evaluateExpression: (body: EvaluationRequest) => unwrap(raw.POST("/api/v1/expressions/evaluate", { body })),
  };
}

export type PipeForgeClient = ReturnType<typeof createClient>;
