/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.devlive.pipeforge.server.run.RunRepository;
import org.devlive.pipeforge.server.run.RunService;
import org.devlive.pipeforge.server.run.RunState;
import org.devlive.pipeforge.server.run.Runs;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "runs", description = "Runs of jobs on the cluster")
public class RunController {

    private final RunService service;
    private final RunRepository runs;

    public RunController(RunService service, RunRepository runs) {
        this.service = service;
        this.runs = runs;
    }

    /**
     * A job to run.
     *
     * @param definition the job in YAML or JSON
     * @param variables values for job variables; they win over env.variables
     * @param scheduledTime the planned time of the run, the base of date variables; now by default
     */
    public record SubmitRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String definition,
            @Nullable Map<String, String> variables,
            @Nullable Instant scheduledTime) {}

    /** Why a run is cancelled; recorded with the change of its state. */
    public record CancelRequest(@Nullable String reason) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            operationId = "submitRun",
            summary = "Run a job",
            description = "Plans the job, records a run and submits it to the master. An invalid job is an error"
                    + " response and no run is recorded. The run starts in QUEUE; GET /api/v1/runs/{id} follows it.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "运行作业"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "计划作业、记录一次运行并提交给 Master。作业不合法时返回错误响应，不记录运行。"
                                                + "运行从 QUEUE 开始；用 GET /api/v1/runs/{id} 跟踪它。")
                            }))
    public Runs.Run submit(@RequestBody SubmitRequest request) {
        return service.submit(request.definition(), request.variables(), request.scheduledTime());
    }

    @GetMapping
    @Operation(
            operationId = "listRuns",
            summary = "List runs",
            description = "Runs newest first, optionally of some states or of one job. A page ends with the token"
                    + " of the next one in next.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "列出运行"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "按提交时间从新到旧列出运行，可以只看某些状态或某个作业。每页的 next 是下一页的令牌。")
                            }))
    public Runs.RunPage list(
            @Parameter(description = "States to list; every state when none") @RequestParam(required = false) @Nullable
                    List<RunState> status,
            @Parameter(description = "The name of a job") @RequestParam(required = false) @Nullable String job,
            @Parameter(description = "Runs per page, at most 200") @RequestParam(defaultValue = "50") int limit,
            @Parameter(description = "The token of a page, from next") @RequestParam(required = false) @Nullable
                    String page) {
        Set<RunState> states =
                status == null || status.isEmpty() ? EnumSet.noneOf(RunState.class) : EnumSet.copyOf(status);
        return runs.list(states, job, limit, page);
    }

    @GetMapping("/{id}")
    @Operation(
            operationId = "getRun",
            summary = "Get a run",
            description = "The run with its job definition and every change of its state, oldest first.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "查看运行"),
                                @ExtensionProperty(name = "description", value = "运行及其作业定义，以及按时间顺序排列的每一次状态变化。")
                            }))
    public Runs.RunDetail get(@PathVariable String id) {
        return runs.detail(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(
            operationId = "cancelRun",
            summary = "Cancel a run",
            description = "Asks the master to cancel a run that has not ended; the run moves to CANCELLING, then"
                    + " CANCELLED. An ended run is returned as it is.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "取消运行"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "请 Master 取消一个尚未结束的运行；运行先进入 CANCELLING，然后是 CANCELLED。已结束的运行原样返回。")
                            }))
    public Runs.Run cancel(@PathVariable String id, @RequestBody(required = false) @Nullable CancelRequest request) {
        return service.cancel(id, request == null ? null : request.reason());
    }
}
