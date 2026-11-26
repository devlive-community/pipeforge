/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The operations of the REST API the client calls: method, path, query parameters and the type of
 * the answer. The client builds its requests from them, and the contract tests hold them to the
 * server's OpenAPI document.
 */
enum Endpoint {
    GET_VERSION("GET", "/api/v1/version", List.of(), VersionInfo.class, false),
    SUBMIT_RUN("POST", "/api/v1/runs", List.of(), Run.class, false),
    LIST_RUNS("GET", "/api/v1/runs", List.of("status", "job", "limit", "page"), RunPage.class, false),
    GET_RUN("GET", "/api/v1/runs/{id}", List.of(), RunDetail.class, false),
    CANCEL_RUN("POST", "/api/v1/runs/{id}/cancel", List.of(), Run.class, false),
    RETRY_RUN("POST", "/api/v1/runs/{id}/retry", List.of(), Run.class, false),
    GET_RUN_METRICS("GET", "/api/v1/runs/{id}/metrics", List.of(), RunMetrics.class, false),
    GET_RUN_METRICS_HISTORY(
            "GET", "/api/v1/runs/{id}/metrics/history", List.of("since", "limit"), MetricSample.class, true),
    GET_RUN_ANALYSIS("GET", "/api/v1/runs/{id}/analysis", List.of(), null, false),
    LIST_RUN_SPLITS("GET", "/api/v1/runs/{id}/splits", List.of(), RunSplit.class, true),
    LIST_RUN_TASKS("GET", "/api/v1/runs/{id}/tasks", List.of(), RunTask.class, true),
    READ_RUN_LOG(
            "GET",
            "/api/v1/runs/{id}/logs",
            List.of("vertex", "subtask", "attempt", "offset", "maxBytes", "contains", "level"),
            LogChunk.class,
            false),
    GET_CLUSTER("GET", "/api/v1/cluster", List.of(), ClusterView.class, false),
    FAILOVER_MASTER("POST", "/api/v1/cluster/failover", List.of(), Failover.class, false),
    DRAIN_WORKER("POST", "/api/v1/cluster/workers/{id}/drain", List.of(), null, false),
    UNCORDON_WORKER("POST", "/api/v1/cluster/workers/{id}/uncordon", List.of(), null, false),
    VALIDATE_JOB("POST", "/api/v1/jobs/validate", List.of(), ValidationResult.class, false),
    EXPLAIN_JOB("POST", "/api/v1/explain", List.of(), ExplainResult.class, false);

    final String method;
    final String path;
    /** The names of the query parameters; a {@code List.of}, so immutable. */
    @SuppressWarnings("ImmutableEnumChecker")
    final List<String> query;
    /**
     * The record of the answer, or of its elements when {@code list}; {@code null} for JSON the
     * client does not type (the run analysis).
     */
    final @Nullable Class<?> answer;

    final boolean list;

    Endpoint(String method, String path, List<String> query, @Nullable Class<?> answer, boolean list) {
        this.method = method;
        this.path = path;
        this.query = query;
        this.answer = answer;
        this.list = list;
    }
}
