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
import org.devlive.pipeforge.server.run.MasterClient;
import org.devlive.pipeforge.server.run.RunMetrics;
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

    /**
     * Where a retried run starts: {@code CHECKPOINT} (its last completed checkpoint) or {@code
     * SCRATCH} (the beginning); the checkpoint when it has one, else the beginning, when not given.
     */
    public record RetryRequest(org.devlive.pipeforge.server.run.RunService.@Nullable RetryFrom from) {}

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

    @PostMapping("/{id}/retry")
    @Operation(
            operationId = "retryRun",
            summary = "Retry a run",
            description = "Starts the next attempt of a run that failed, timed out or was cancelled, whatever"
                    + " env.retry allows: from its last completed checkpoint or from the beginning, with the same"
                    + " variables and plugin versions. The run moves back to QUEUE; its attempts are listed by"
                    + " GET /api/v1/runs/{id}.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "重试运行"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "为失败、超时或被取消的运行开始下一次尝试，不受 env.retry 限制：从最近完成的 checkpoint"
                                                + " 或从头开始，变量和插件版本不变。运行回到 QUEUE；GET /api/v1/runs/{id} 列出它的各次尝试。")
                            }))
    public Runs.Run retry(@PathVariable String id, @RequestBody(required = false) @Nullable RetryRequest request) {
        return service.retry(id, request == null ? null : request.from());
    }

    @GetMapping("/{id}/metrics")
    @Operation(
            operationId = "getRunMetrics",
            summary = "Get the metrics of a run",
            description = "Rows, rates, waits, memory, splits and the counters of every node: from the master while"
                    + " the run runs, else the last sample the server kept (design 15).",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "查看运行的指标"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "行数、速率、等待时间、内存、分片以及每个节点的计数：运行中时来自 Master，否则为服务端保留的最后一次采样。")
                            }))
    public RunMetrics metrics(@PathVariable String id) {
        return service.metrics(id);
    }

    @GetMapping("/{id}/metrics/history")
    @Operation(
            operationId = "getRunMetricsHistory",
            summary = "Get the metrics of a run over time",
            description = "The samples the server kept while the run ran (pipeforge.master.metrics-interval), oldest"
                    + " first; rates follow from two samples.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "查看运行指标的变化"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "运行期间服务端保留的采样（pipeforge.master.metrics-interval），按时间顺序；速率由相邻两次采样得出。")
                            }))
    public List<RunMetrics.Sample> history(
            @PathVariable String id,
            @Parameter(description = "Only samples from this time on") @RequestParam(required = false) @Nullable
                    Instant since,
            @Parameter(description = "Samples at most, up to 10000") @RequestParam(defaultValue = "1000") int limit) {
        return service.history(id, since, limit);
    }

    @GetMapping("/{id}/analysis")
    @Operation(
            operationId = "getRunAnalysis",
            summary = "Get the analysis of a run",
            description = "What limited the run and where (design 29): job-level figures, node-level figures and the"
                    + " bottleneck. Kept when the run ends.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "查看运行分析"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "运行受什么限制、限制在哪里：作业级与节点级的数字以及瓶颈结论。运行结束时保留。")
                            }))
    public tools.jackson.databind.JsonNode analysis(@PathVariable String id) {
        return service.analysis(id);
    }

    @GetMapping("/{id}/splits")
    @Operation(
            operationId = "listRunSplits",
            summary = "List the splits of a run",
            description = "Every split the source assigned, with its reader and whether the reader finished; empty"
                    + " once the master no longer has the run.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "列出运行的分片"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "Source 分配的每个分片、读取它的读取器以及读取器是否已结束；Master 不再保留该运行后为空。")
                            }))
    public List<RunMetrics.Split> splits(@PathVariable String id) {
        return service.splits(id);
    }

    @GetMapping("/{id}/tasks")
    @Operation(
            operationId = "listRunTasks",
            summary = "List the tasks of a run",
            description = "Every task with its state and worker, to read its log; empty once the master no longer has"
                    + " the run.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "列出运行的 Task"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "每个 Task 的状态和所在的 Worker，用于读取日志；Master 不再保留该运行后为空。")
                            }))
    public List<RunMetrics.Task> tasks(@PathVariable String id) {
        return service.tasks(id);
    }

    @GetMapping("/{id}/logs")
    @Operation(
            operationId = "readRunLog",
            summary = "Read the log of a task",
            description = "A part of a task's log, through the master from the worker it ran on. offset in the answer"
                    + " is where the next read continues, so following a log means reading again from it; eof is true"
                    + " once the log was read to its end and the task has ended.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "读取 Task 的日志"),
                                @ExtensionProperty(
                                        name = "description",
                                        value = "经 Master 从 Task 所在的 Worker 读取一段日志。返回的 offset 是下一次读取继续的位置，"
                                                + "跟踪日志就是从它再次读取；日志读完且 Task 已结束时 eof 为 true。")
                            }))
    public RunMetrics.LogChunk log(
            @PathVariable String id,
            @Parameter(description = "The vertex of the task, as in the tasks of the run") @RequestParam String vertex,
            @Parameter(description = "The subtask") @RequestParam int subtask,
            @Parameter(description = "The attempt") @RequestParam(defaultValue = "0") int attempt,
            @Parameter(description = "Where to start; negative counts back from the end")
                    @RequestParam(defaultValue = "0")
                    long offset,
            @Parameter(description = "The most bytes; 0 for 64KB") @RequestParam(defaultValue = "0") long maxBytes,
            @Parameter(description = "Only entries with this text") @RequestParam(defaultValue = "") String contains,
            @Parameter(description = "Only entries of this level or above: TRACE, DEBUG, INFO, WARN, ERROR")
                    @RequestParam(required = false)
                    @Nullable
                    String level) {
        return service.log(id, vertex, subtask, attempt, new MasterClient.LogQuery(offset, maxBytes, contains, level));
    }
}
