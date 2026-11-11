/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { join } from "node:path";
import { collectApi, typeName } from "@/scripts/api-data.mjs";

describe("collectApi", () => {
  it("reads every operation of the real OpenAPI document in both languages", () => {
    const { operations, schemas } = collectApi(join(process.cwd(), ".."));
    expect(operations.map((o: { id: string }) => o.id)).toEqual(
      expect.arrayContaining(["validateJob", "explainJob", "evaluateExpression", "getComponent", "listPlugins"]),
    );
    const validate = operations.find((o: { id: string }) => o.id === "validateJob")!;
    expect(validate).toMatchObject({ method: "POST", path: "/api/v1/jobs/validate", requestBody: "JobRequest" });
    expect(validate.summary["zh-CN"]).toBe("校验作业");
    expect(validate.responses.map((r: { status: string }) => r.status)).toEqual(["200", "400", "422", "500"]);
    const errorInfo = schemas.find((s: { name: string }) => s.name === "ErrorInfo")!;
    expect(errorInfo.fields.map((f: { name: string }) => f.name)).toContain("scope");
    const request = schemas.find((s: { name: string }) => s.name === "JobRequest")!;
    expect(request.fields).toContainEqual({ name: "definition", type: "string", required: true });
  });

  it("writes types readably", () => {
    expect(typeName({ $ref: "#/components/schemas/ErrorInfo" })).toBe("ErrorInfo");
    expect(typeName({ type: "array", items: { $ref: "#/components/schemas/Node" } })).toBe("Node[]");
    expect(typeName({ type: "object", additionalProperties: { type: "string" } })).toBe("map<string, string>");
    expect(typeName({ type: "object", additionalProperties: {} })).toBe("map<string, any>");
    expect(typeName({ type: ["string", "null"] })).toBe("string");
    expect(typeName({ type: "string", format: "date-time" })).toBe("string (date-time)");
    expect(typeName({ type: "integer", format: "int32" })).toBe("int32");
    expect(typeName({ type: "string", enum: ["A", "B"] })).toBe('"A" | "B"');
    expect(typeName({})).toBe("any");
    expect(typeName(undefined)).toBe("any");
  });
});
