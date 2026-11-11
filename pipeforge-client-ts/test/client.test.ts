/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { ApiError, createClient, type Language } from "../src/index";

interface Call {
  url: string;
  method: string;
  language: string | null;
  body: string;
}

/** A fetch that records requests and answers with the given status and body. */
function server(status: number, body: unknown) {
  const calls: Call[] = [];
  const fetch = async (input: Request | string | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(input, init);
    calls.push({
      url: request.url,
      method: request.method,
      language: request.headers.get("Accept-Language"),
      body: await request.text(),
    });
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  };
  return { calls, fetch: fetch as typeof globalThis.fetch };
}

describe("createClient", () => {
  it("calls the endpoints with the language and returns the body", async () => {
    const { calls, fetch } = server(200, { valid: true, errors: [], warnings: [] });
    const client = createClient({ baseUrl: "http://pf.test", language: "zh-CN", fetch });
    const result = await client.validateJob({ definition: "version: 1", variables: { day: "2026-11-07" } });
    expect(result.valid).toBe(true);
    expect(calls).toEqual([
      {
        url: "http://pf.test/api/v1/jobs/validate",
        method: "POST",
        language: "zh-CN",
        body: JSON.stringify({ definition: "version: 1", variables: { day: "2026-11-07" } }),
      },
    ]);
  });

  it("builds paths and query parameters", async () => {
    const { calls, fetch } = server(200, { plugin: "jdbc@1.0.0", options: [] });
    const client = createClient({ baseUrl: "http://pf.test", fetch });
    await client.getComponent("source", "jdbc", "jdbc@1.0.0");
    await client.getComponent("sink", "console");
    expect(calls.map((c) => c.url)).toEqual([
      "http://pf.test/api/v1/components/source/jdbc?plugin=jdbc%401.0.0",
      "http://pf.test/api/v1/components/sink/console",
    ]);
    expect(calls[0]?.language).toBeNull();
  });

  it("asks for the language on every request when given a function", async () => {
    const { calls, fetch } = server(200, []);
    let language: Language = "en-US";
    const client = createClient({ baseUrl: "http://pf.test", language: () => language, fetch });
    await client.listFunctions();
    language = "zh-CN";
    await client.listErrorCodes();
    expect(calls.map((c) => c.language)).toEqual(["en-US", "zh-CN"]);
  });

  it("rejects error responses with the error object", async () => {
    const info = { code: "PF-CONF-0004", name: "VARIABLE_UNDEFINED", message: "Variable '${var:x}' is not defined" };
    const { fetch } = server(422, info);
    const client = createClient({ baseUrl: "http://pf.test", fetch });
    const error = await client.explainJob({ definition: "x" }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(422);
    expect((error as ApiError).code).toBe("PF-CONF-0004");
    expect((error as ApiError).message).toBe("Variable '${var:x}' is not defined");
  });

  it("has a helper for every operation of the OpenAPI document", () => {
    const document = JSON.parse(readFileSync(new URL("../../pipeforge-server/openapi.json", import.meta.url), "utf8"));
    const operations: string[] = [];
    for (const path of Object.values(document.paths) as Record<string, { operationId: string }>[]) {
      for (const operation of Object.values(path)) operations.push(operation.operationId);
    }
    const client = createClient({ baseUrl: "http://pf.test" });
    expect(operations.sort()).toEqual(
      Object.keys(client)
        .filter((k) => k !== "raw")
        .sort(),
    );
  });
});
