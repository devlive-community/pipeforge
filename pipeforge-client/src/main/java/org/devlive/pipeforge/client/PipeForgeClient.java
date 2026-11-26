/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The Java client of the server's REST API. Every call blocks until the server answers; a refusal
 * or an unreachable server is an {@link ApiException} with the registered error code. Answers keep
 * working when the server adds fields. Safe for use by several threads.
 *
 * <pre>{@code
 * PipeForgeClient client = PipeForgeClient.builder(URI.create("http://127.0.0.1:8080")).build();
 * Run run = client.submitRun(Files.readString(job), Map.of());
 * RunDetail ended = client.awaitRun(run.id(), Duration.ofSeconds(1), Duration.ofHours(1));
 * }</pre>
 */
public final class PipeForgeClient {

    /** The states in which a run has ended. */
    public static final List<String> ENDED = List.of("SUCCESS", "FAILED", "CANCELLED", "TIMEOUT");

    static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final URI server;
    private final HttpClient http;
    private final Duration timeout;
    private final @Nullable String language;

    private PipeForgeClient(Builder builder) {
        String base = builder.server.toString();
        this.server = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.timeout = builder.timeout;
        this.language = builder.language;
        this.http = HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static Builder builder(URI server) {
        return new Builder(server);
    }

    /** How the client reaches the server. */
    public static final class Builder {
        private final URI server;
        private Duration timeout = Duration.ofSeconds(60);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private @Nullable String language;

        private Builder(URI server) {
            this.server = server;
        }

        /** The longest a call waits for its answer; 60 seconds by default. */
        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        /** The longest connecting takes; 10 seconds by default. */
        public Builder connectTimeout(Duration value) {
            this.connectTimeout = value;
            return this;
        }

        /** The language of messages, such as {@code zh-CN}; the server's default when not set. */
        public Builder language(@Nullable String value) {
            this.language = value;
            return this;
        }

        public PipeForgeClient build() {
            return new PipeForgeClient(this);
        }
    }

    public URI server() {
        return server;
    }

    // ------------------------------------------------------------------------------------- calls

    public VersionInfo version() {
        return call(Endpoint.GET_VERSION, Map.of(), Map.of(), null);
    }

    /** Submits a job to run on the cluster; the run comes back queued. */
    public Run submitRun(String definition, Map<String, String> variables) {
        return submitRun(definition, variables, null);
    }

    /** Submits a job to run on the cluster, with the time {@code ${date:...}} variables are based on. */
    public Run submitRun(String definition, Map<String, String> variables, @Nullable Instant scheduledTime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definition", definition);
        body.put("variables", variables);
        if (scheduledTime != null) {
            body.put("scheduledTime", scheduledTime);
        }
        return call(Endpoint.SUBMIT_RUN, Map.of(), Map.of(), body);
    }

    public RunPage listRuns(RunQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("status", query.statuses());
        parameters.put("job", query.job());
        parameters.put("limit", query.limit() > 0 ? query.limit() : null);
        parameters.put("page", query.page());
        return call(Endpoint.LIST_RUNS, Map.of(), parameters, null);
    }

    /** The run with its definition and changes of state; {@code PF-CONF-0010} when there is none. */
    public RunDetail run(String id) {
        return call(Endpoint.GET_RUN, Map.of("id", id), Map.of(), null);
    }

    /** Asks the run to stop; it ends as CANCELLED once its tasks stopped. */
    public Run cancelRun(String id, @Nullable String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null) {
            body.put("reason", reason);
        }
        return call(Endpoint.CANCEL_RUN, Map.of("id", id), Map.of(), body);
    }

    /**
     * Starts the next attempt of a run that failed, timed out or was cancelled.
     *
     * @param from {@code CHECKPOINT} (its last completed checkpoint) or {@code SCRATCH} (the
     *     beginning); {@code null} for the checkpoint when the run has one, else the beginning
     */
    public Run retryRun(String id, @Nullable String from) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (from != null) {
            body.put("from", from);
        }
        return call(Endpoint.RETRY_RUN, Map.of("id", id), Map.of(), body);
    }

    /** The checkpoints and savepoints of a run, newest first. */
    public List<RunCheckpoint> checkpoints(String id) {
        return call(Endpoint.LIST_RUN_CHECKPOINTS, Map.of("id", id), Map.of(), null);
    }

    /** The attempts of a run, oldest first. */
    public List<RunAttempt> attempts(String id) {
        return call(Endpoint.LIST_RUN_ATTEMPTS, Map.of("id", id), Map.of(), null);
    }

    /** Takes a savepoint of a running run and returns it once it completed; {@code name} may be null. */
    public RunCheckpoint savepoint(String id, @Nullable String name) {
        return call(Endpoint.SAVEPOINT_RUN, Map.of("id", id), Map.of(), named(name));
    }

    /** Stops a running run with a savepoint; it comes back PAUSING and becomes PAUSED. */
    public Run pauseRun(String id, @Nullable String name) {
        return call(Endpoint.PAUSE_RUN, Map.of("id", id), Map.of(), named(name));
    }

    /** Starts a paused run again from its savepoint. */
    public Run resumeRun(String id) {
        return call(Endpoint.RESUME_RUN, Map.of("id", id), Map.of(), null);
    }

    private static Map<String, Object> named(@Nullable String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (name != null) {
            body.put("name", name);
        }
        return body;
    }

    public RunMetrics metrics(String id) {
        return call(Endpoint.GET_RUN_METRICS, Map.of("id", id), Map.of(), null);
    }

    /** The samples of the run's metrics since {@code since} (all when null), oldest first. */
    public List<MetricSample> metricsHistory(String id, @Nullable Instant since) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("since", since);
        return call(Endpoint.GET_RUN_METRICS_HISTORY, Map.of("id", id), parameters, null);
    }

    /** The run analysis as JSON (schema version 1, see the explain documentation). */
    public JsonNode analysis(String id) {
        return call(Endpoint.GET_RUN_ANALYSIS, Map.of("id", id), Map.of(), null);
    }

    public List<RunSplit> splits(String id) {
        return call(Endpoint.LIST_RUN_SPLITS, Map.of("id", id), Map.of(), null);
    }

    public List<RunTask> tasks(String id) {
        return call(Endpoint.LIST_RUN_TASKS, Map.of("id", id), Map.of(), null);
    }

    /** A part of a task log; read again from {@link LogChunk#offset()} to follow it. */
    public LogChunk readLog(String id, LogQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("vertex", query.vertex());
        parameters.put("subtask", query.subtask());
        parameters.put("attempt", query.attempt());
        parameters.put("offset", query.offset());
        parameters.put("maxBytes", query.maxBytes() > 0 ? query.maxBytes() : null);
        parameters.put("contains", query.contains().isEmpty() ? null : query.contains());
        parameters.put("level", query.level());
        return call(Endpoint.READ_RUN_LOG, Map.of("id", id), parameters, null);
    }

    public ClusterView cluster() {
        return call(Endpoint.GET_CLUSTER, Map.of(), Map.of(), null);
    }

    /**
     * Asks the leading master to give its lease up so that a standby takes over; {@code
     * PF-CLUS-0019} when the masters elect no leader.
     */
    public Failover failover() {
        return call(Endpoint.FAILOVER_MASTER, Map.of(), Map.of(), null);
    }

    /** Gives a worker no new tasks; {@code PF-CLUS-0020} for a worker the master does not know. */
    public void drainWorker(String id) {
        call(Endpoint.DRAIN_WORKER, Map.of("id", id), Map.of(), null);
    }

    /** Gives a drained worker new tasks again. */
    public void uncordonWorker(String id) {
        call(Endpoint.UNCORDON_WORKER, Map.of("id", id), Map.of(), null);
    }

    public ValidationResult validate(String definition, Map<String, String> variables) {
        return validate(definition, variables, null);
    }

    /** Checks a job on the server, with the time {@code ${date:...}} variables are based on. */
    public ValidationResult validate(
            String definition, Map<String, String> variables, @Nullable Instant scheduledTime) {
        return call(Endpoint.VALIDATE_JOB, Map.of(), Map.of(), job(definition, variables, scheduledTime));
    }

    /** The plan of a job, as the server plans it with its plugins. */
    public ExplainResult explain(String definition, Map<String, String> variables) {
        return explain(definition, variables, null);
    }

    /** The plan of a job as the server plans it, with the time {@code ${date:...}} variables are based on. */
    public ExplainResult explain(String definition, Map<String, String> variables, @Nullable Instant scheduledTime) {
        return call(Endpoint.EXPLAIN_JOB, Map.of(), Map.of(), job(definition, variables, scheduledTime));
    }

    private static Map<String, Object> job(
            String definition, Map<String, String> variables, @Nullable Instant scheduledTime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definition", definition);
        body.put("variables", variables);
        if (scheduledTime != null) {
            body.put("scheduledTime", scheduledTime);
        }
        return body;
    }

    // ------------------------------------------------------------------------------------ helpers

    /**
     * Asks for the run every {@code poll} until it ended, and returns it.
     *
     * @throws TimeoutException when it did not end within {@code timeout}; the run goes on
     */
    public RunDetail awaitRun(String id, Duration poll, Duration timeout)
            throws InterruptedException, TimeoutException {
        return awaitRun(id, poll, timeout, detail -> ENDED.contains(detail.run().status()));
    }

    /**
     * Asks for the run every {@code poll} until {@code done} holds, and returns it.
     *
     * @throws TimeoutException when {@code done} did not hold within {@code timeout}
     */
    public RunDetail awaitRun(String id, Duration poll, Duration timeout, Predicate<RunDetail> done)
            throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            RunDetail detail = run(id);
            if (done.test(detail)) {
                return detail;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new TimeoutException(
                        "Run " + id + " is " + detail.run().status() + " after " + timeout.toSeconds() + " s");
            }
            Thread.sleep(poll.toMillis());
        }
    }

    /**
     * Reads a task log from {@code query}'s offset and hands every chunk of text to {@code out},
     * reading again every {@code poll} until the log ended or {@code keepGoing} says to stop.
     * Returns the offset to continue from.
     */
    public long followLog(String id, LogQuery query, Duration poll, Consumer<String> out, Predicate<LogChunk> keepGoing)
            throws InterruptedException {
        long offset = query.offset();
        while (true) {
            LogChunk chunk = readLog(id, query.withOffset(offset));
            if (!chunk.text().isEmpty()) {
                out.accept(chunk.text());
            }
            boolean moved = chunk.offset() != offset;
            offset = chunk.offset();
            if (chunk.eof() || !keepGoing.test(chunk)) {
                return offset;
            }
            if (!moved || chunk.text().isEmpty()) {
                Thread.sleep(poll.toMillis());
            }
        }
    }

    // ----------------------------------------------------------------------------------- requests

    /** One request; the endpoint fixes the type of the answer, which the contract tests check. */
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    private <T> T call(Endpoint endpoint, Map<String, String> path, Map<String, ?> query, @Nullable Object body) {
        URI uri = uri(endpoint, path, query);
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
        if (language != null) {
            request.header("Accept-Language", language);
        }
        if (endpoint.method.equals("POST")) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json(body == null ? Map.of() : body)));
        } else {
            request.GET();
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(
                    0,
                    error(
                            "PF-CLUS-0008",
                            "The server at " + server + " cannot be reached: "
                                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, error("PF-CLUS-0008", "Interrupted while asking " + server), e);
        }
        if (response.statusCode() >= 300) {
            throw refusal(response);
        }
        if (response.statusCode() == 204) {
            // done, with nothing to answer
            return null;
        }
        try {
            if (endpoint.answer == null) {
                return (T) JSON.readTree(response.body());
            }
            if (endpoint.list) {
                return (T) JSON.readValue(
                        response.body(), JSON.getTypeFactory().constructCollectionType(List.class, endpoint.answer));
            }
            return (T) JSON.readValue(response.body(), endpoint.answer);
        } catch (JsonProcessingException e) {
            throw new ApiException(
                    response.statusCode(),
                    error(
                            "PF-SYS-0001",
                            "Internal error: the answer of " + endpoint.method + " " + uri.getPath()
                                    + " is not what the client expects: " + e.getOriginalMessage()),
                    e);
        }
    }

    private ApiException refusal(HttpResponse<String> response) {
        try {
            JsonNode body = JSON.readTree(response.body());
            if (body != null && body.hasNonNull("code") && body.hasNonNull("message")) {
                return new ApiException(response.statusCode(), JSON.treeToValue(body, ApiError.class));
            }
        } catch (JsonProcessingException e) {
            // not an error of the server: described below
        }
        String text = response.body().strip();
        return new ApiException(
                response.statusCode(),
                error(
                        "PF-SYS-0001",
                        "Internal error: the server answered HTTP " + response.statusCode()
                                + " without an error description"
                                + (text.isEmpty() ? "" : ": " + text.substring(0, Math.min(200, text.length())))));
    }

    URI uri(Endpoint endpoint, Map<String, String> path, Map<String, ?> query) {
        String resolved = endpoint.path;
        for (Map.Entry<String, String> entry : path.entrySet()) {
            resolved = resolved.replace(
                    "{" + entry.getKey() + "}",
                    URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, ?> entry : query.entrySet()) {
            if (!endpoint.query.contains(entry.getKey())) {
                throw new IllegalArgumentException(entry.getKey() + " is not a parameter of " + endpoint);
            }
            Object value = entry.getValue();
            List<?> values = value instanceof List<?> list ? list : value == null ? List.of() : List.of(value);
            for (Object each : values) {
                parts.add(encode(entry.getKey()) + "=" + encode(String.valueOf(each)));
            }
        }
        return URI.create(server + resolved + (parts.isEmpty() ? "" : "?" + String.join("&", parts)));
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String json(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot write the request as JSON: " + e.getOriginalMessage(), e);
        }
    }

    static ApiError error(String code, String message) {
        return new ApiError(
                code, null, message, null, code.startsWith("PF-SYS") ? "INTERNAL" : "RETRYABLE", Map.of(), Map.of());
    }

    @Override
    public String toString() {
        return "PipeForgeClient[" + server + ", " + String.valueOf(language).toLowerCase(Locale.ROOT) + "]";
    }
}
