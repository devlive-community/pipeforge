/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import { ApiList } from "@/components/mdx/ApiList";

vi.mock("@/lib/generated", () => ({
  apiReference: () => ({
    operations: [
      {
        id: "validateJob",
        method: "POST",
        path: "/api/v1/jobs/validate",
        tag: "jobs",
        summary: { "en-US": "Validate a job", "zh-CN": "校验作业" },
        description: { "en-US": "Checks a job.", "zh-CN": "检查作业。" },
        parameters: [],
        requestBody: "JobRequest",
        responses: [
          { status: "200", description: "OK", type: "ValidationResult" },
          { status: "400", description: "The request is invalid", type: "ErrorInfo" },
        ],
      },
      {
        id: "getComponent",
        method: "GET",
        path: "/api/v1/components/{type}/{identifier}",
        tag: "plugins",
        summary: { "en-US": "Describe a component", "zh-CN": "查看组件" },
        description: { "en-US": "", "zh-CN": "" },
        parameters: [{ name: "plugin", in: "query", required: false, type: "string", description: "Plugin id" }],
        requestBody: null,
        responses: [],
      },
    ],
    schemas: [
      { name: "ErrorInfo", fields: [{ name: "code", type: "string", required: false }] },
      { name: "JobRequest", fields: [{ name: "definition", type: "string", required: true }] },
      { name: "ValidationResult", fields: [{ name: "errors", type: "ErrorInfo[]", required: false }] },
    ],
  }),
}));

describe("ApiList", () => {
  it("renders operations with anchors and links schema names", () => {
    render(<ApiList lang="en-US" />);
    expect(document.getElementById("validatejob")).toHaveTextContent("POST/api/v1/jobs/validate");
    expect(screen.getByText("Validate a job")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "JobRequest" })[0]).toHaveAttribute("href", "#schema-jobrequest");
    expect(screen.getAllByRole("link", { name: "ErrorInfo" }).length).toBeGreaterThan(1);
    expect(document.getElementById("schema-validationresult")).toHaveTextContent("errors");
    expect(document.getElementById("getcomponent")).toHaveTextContent("plugin (query) string — Plugin id");
    expect(document.getElementById("validatejob")).toHaveTextContent("400 ErrorInfo — The request is invalid");
  });

  it("uses the Chinese texts", () => {
    render(<ApiList lang="zh-CN" />);
    expect(screen.getByText("校验作业")).toBeInTheDocument();
    expect(screen.getByText("数据结构")).toBeInTheDocument();
    expect(document.getElementById("schema-jobrequest")).toHaveTextContent("必填");
    expect(document.getElementById("validatejob")).toHaveTextContent("请求不合法（PF-CONF-0007）");
    expect(document.getElementById("validatejob")).not.toHaveTextContent("The request is invalid");
  });
});
