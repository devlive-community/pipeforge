/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorInfo;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.explain.ExplainDocument;
import org.devlive.pipeforge.core.explain.PlanExplainer;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.server.config.JobPlanning;
import org.devlive.pipeforge.server.web.Languages;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Validates and explains job definitions without running them. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "jobs", description = "Job validation and plans")
public class JobController {

    private final JobPlanning planning;
    private final org.devlive.pipeforge.server.preview.PreviewService previews;

    public JobController(JobPlanning planning, org.devlive.pipeforge.server.preview.PreviewService previews) {
        this.planning = planning;
        this.previews = previews;
    }

    /**
     * A job to preview.
     *
     * @param definition the job in YAML or JSON
     * @param variables values for job variables
     * @param scheduledTime the planned time, the base of date variables; now by default
     * @param node the node whose output to show; without it, what the job's sink would receive (a dry run)
     * @param limit rows per table, at most 1000; 100 by default
     * @param timeoutSeconds how long the preview may run, at most 120; 30 by default
     */
    public record PreviewRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String definition,
            @Nullable Map<String, String> variables,
            @Nullable Instant scheduledTime,
            @Nullable String node,
            @Nullable Integer limit,
            @Nullable Integer timeoutSeconds) {}

    /**
     * A job definition to check.
     *
     * @param definition the job in YAML or JSON
     * @param variables values for job variables; they win over env.variables
     * @param scheduledTime the planned time of the run, the base of date variables; now by default
     */
    public record JobRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String definition,
            @Nullable Map<String, String> variables,
            @Nullable Instant scheduledTime) {}

    /**
     * The outcome of a validation. {@code errors} holds the first problem found, with its location
     * in the definition in {@code scope} when there is one.
     */
    public record ValidationResult(boolean valid, List<ErrorInfo> errors, List<String> warnings) {}

    /** A plan as a structured document and as the text that {@code pipeforge explain} prints. */
    public record ExplainResult(ExplainDocument plan, String text) {}

    @PostMapping("/jobs/validate")
    @Operation(
            operationId = "validateJob",
            summary = "Validate a job",
            description = "Parses the definition, resolves its variables, checks the plugins, options, graph and"
                    + " schemas. Problems are part of the result, not an error response.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "校验作业"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "解析定义、解析变量，并检查插件、选项、图和 Schema。问题作为结果的一部分返回，而不是错误响应。")
                            }))
    public ValidationResult validate(
            @RequestBody JobRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) @Nullable String accept) {
        checkRequest(request);
        try {
            JobPlan plan = plan(request);
            return new ValidationResult(true, List.of(), plan.warnings());
        } catch (PipeForgeException e) {
            return new ValidationResult(false, List.of(e.toErrorInfo(Languages.of(accept))), List.of());
        }
    }

    @PostMapping("/explain")
    @Operation(
            operationId = "explainJob",
            summary = "Explain a job",
            description = "The logical plan of the job: settings, variables, nodes, options with their origin,"
                    + " schemas and warnings. An invalid job is an error response.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "查看作业计划"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "作业的逻辑计划：设置、变量、节点、选项及其来源、Schema 和警告。作业不合法时返回错误响应。")
                            }))
    public ExplainResult explain(@RequestBody JobRequest request) {
        JobPlan plan = plan(request);
        return new ExplainResult(PlanExplainer.document(plan), PlanExplainer.text(plan));
    }

    @PostMapping("/jobs/preview")
    @Operation(
            operationId = "previewJob",
            summary = "Preview a job",
            description = "Runs the job in the server up to a node, or up to its sink for a dry run, with the sinks"
                    + " replaced by a buffer, at parallelism 1, and returns the first rows of every table there. It"
                    + " stops at the row limit, at the end of the input or at the timeout; nothing is written to"
                    + " any target.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "预览作业"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "在服务端运行作业，直到某个节点（试运行时直到 Sink），Sink 被替换为缓冲区，并行度为 1，"
                                                + "返回该处每张表的前几行。达到行数上限、输入读完或超时时停止；不会写入任何目标端。")
                            }))
    public org.devlive.pipeforge.server.preview.PreviewService.PreviewResult preview(
            @RequestBody PreviewRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) @Nullable String accept)
            throws InterruptedException {
        if (request == null) {
            checkRequest(null);
        }
        return previews.preview(
                request.definition(),
                request.variables(),
                request.scheduledTime(),
                request.node(),
                request.limit() == null ? 100 : request.limit(),
                java.time.Duration.ofSeconds(request.timeoutSeconds() == null ? 30 : request.timeoutSeconds()),
                Languages.of(accept));
    }

    /** A request without a definition is a client error (400), not a validation result. */
    private static void checkRequest(@Nullable JobRequest request) {
        if (request == null) {
            throw PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                    .param("reason", "definition must hold the job in YAML or JSON")
                    .build();
        }
        if (request.definition() == null || request.definition().isBlank()) {
            throw PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                    .param("reason", "definition must hold the job in YAML or JSON")
                    .build();
        }
    }

    private JobPlan plan(JobRequest request) {
        checkRequest(request);
        return planning.plan(request.definition(), request.variables(), request.scheduledTime());
    }
}
