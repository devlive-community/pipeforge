/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import java.util.Objects;

/**
 * The run analysis of a job: its metrics, the rates of its nodes and its bottleneck. A running job
 * is judged on the last {@link RunAnalyzer#WINDOW_MS} milliseconds, a job that ended on the whole
 * run.
 *
 * @param schemaVersion the version of the JSON form
 * @param job the job name
 * @param status the status of the run
 * @param windowFromMs where the window starts, in milliseconds since the run started
 * @param windowToMs where the window ends
 * @param metrics the metrics at the end of the window
 * @param rates the rates of the nodes in the window, in the order of the plan
 * @param bottleneck what limits the run in the window
 */
public record RunAnalysis(
        String schemaVersion,
        String job,
        JobStatus status,
        long windowFromMs,
        long windowToMs,
        JobMetrics metrics,
        List<NodeRate> rates,
        Bottleneck bottleneck) {

    public static final String SCHEMA_VERSION = "1";

    private static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    /**
     * The rows a node took and gave per second in the window.
     *
     * @param node the node ID
     * @param rowsInPerSecond rows in per second
     * @param rowsOutPerSecond rows out per second
     */
    public record NodeRate(String node, double rowsInPerSecond, double rowsOutPerSecond) {}

    public RunAnalysis {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(bottleneck, "bottleneck");
        rates = List.copyOf(rates);
    }

    /** The analysis as JSON, the form the server and the {@code Analyze} RPC return. */
    public String json() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the run analysis as JSON", e);
        }
    }

    /** Reads the JSON form back. */
    public static RunAnalysis fromJson(String json) {
        try {
            return JSON.readValue(json, RunAnalysis.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not a run analysis: " + e.getOriginalMessage(), e);
        }
    }

    /** The analysis as aligned text, as {@code explain --analyze} prints it. */
    public String text() {
        return AnalyzeText.format(this);
    }
}
