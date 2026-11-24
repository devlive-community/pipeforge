/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The runs in the metastore: {@code job_run}, {@code job_run_attempt} and {@code
 * job_run_transition}. Times are stored as UTC date-times. A state change is written only when the
 * stored state is still the one it changes from, so that servers that watch the same run record
 * each change once.
 */
@Repository
public class RunRepository {

    /** The most runs of a page. */
    public static final int MAX_LIMIT = 200;

    /** The last sample of a run's metrics, for its row counts. */
    private static final String LAST_SAMPLE =
            " FROM job_run_metrics m WHERE m.run_id = job_run.id ORDER BY m.sampled_at DESC LIMIT 1)";

    private static final String COLUMNS =
            "id, job_name, status, submitted_by, submitted_at, started_at, ended_at, updated_at, error_code,"
                    + " error_message, (SELECT m.rows_read" + LAST_SAMPLE + " AS rows_read, (SELECT m.rows_written"
                    + LAST_SAMPLE + " AS rows_written, COALESCE(attempt, 1) AS attempt, retry_at";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public RunRepository(DataSource metastoreDataSource, TransactionTemplate transactions) {
        this.jdbc = JdbcClient.create(metastoreDataSource);
        this.transactions = transactions;
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(@Nullable LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private static final RowMapper<Runs.Run> RUN = (rs, row) -> {
        String code = rs.getString("error_code");
        return new Runs.Run(
                rs.getString("id"),
                rs.getString("job_name"),
                RunState.valueOf(rs.getString("status")),
                rs.getString("submitted_by"),
                instant(rs.getObject("submitted_at", LocalDateTime.class)),
                instant(rs.getObject("started_at", LocalDateTime.class)),
                instant(rs.getObject("ended_at", LocalDateTime.class)),
                instant(rs.getObject("updated_at", LocalDateTime.class)),
                code == null ? null : new Runs.RunError(code, String.valueOf(rs.getString("error_message"))),
                rs.getObject("rows_read", Long.class),
                rs.getObject("rows_written", Long.class),
                rs.getInt("attempt"),
                instant(rs.getObject("retry_at", LocalDateTime.class)));
    };

    /** Records a submitted run in {@code QUEUE}, with its first attempt and its first transition. */
    public Runs.Run insert(String id, String job, String definition, @Nullable String submittedBy, Instant now) {
        return insert(id, job, definition, submittedBy, now, null);
    }

    /**
     * Records a submitted run in {@code QUEUE}, with its first attempt and its first transition.
     *
     * @param submission what a later attempt needs to be submitted the same way, as JSON
     */
    public Runs.Run insert(
            String id,
            String job,
            String definition,
            @Nullable String submittedBy,
            Instant now,
            @Nullable String submission) {
        transactions.executeWithoutResult(tx -> {
            jdbc.sql("INSERT INTO job_run (id, job_name, job_yaml, status, submitted_by, submitted_at, updated_at,"
                            + " attempt, submission_json) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)")
                    .params(id, job, definition, RunState.QUEUE.name(), submittedBy, utc(now), utc(now), submission)
                    .update();
            jdbc.sql("INSERT INTO job_run_attempt (run_id, attempt, status, started_at) VALUES (?, 1, ?, ?)")
                    .params(id, RunState.QUEUE.name(), utc(now))
                    .update();
            insertTransition(id, 1, null, RunState.QUEUE, now, "submitted");
        });
        return find(id).orElseThrow();
    }

    /** What a later attempt of the run needs to be submitted the same way, as JSON; empty for older runs. */
    public Optional<String> submission(String id) {
        return jdbc.sql("SELECT submission_json FROM job_run WHERE id = ? AND submission_json IS NOT NULL")
                .param(id)
                .query(String.class)
                .optional();
    }

    /** The current attempt of a run, counted from 1. */
    private int attempt(String id) {
        return jdbc.sql("SELECT COALESCE(attempt, 1) FROM job_run WHERE id = ?")
                .param(id)
                .query(Integer.class)
                .optional()
                .orElse(1);
    }

    /**
     * An attempt failed: the run waits in {@code RETRY_WAIT} until {@code retryAt}, unless it is no
     * longer in {@code from}.
     *
     * @return whether this call made the change
     */
    public boolean awaitRetry(
            String id, RunState from, Instant at, Instant retryAt, Runs.@Nullable RunError error, String reason) {
        Boolean changed = transactions.execute(tx -> {
            int rows = jdbc.sql("UPDATE job_run SET status = ?, retry_at = ?, updated_at = ?, error_code = ?,"
                            + " error_message = ? WHERE id = ? AND status = ?")
                    .params(
                            RunState.RETRY_WAIT.name(),
                            utc(retryAt),
                            utc(at),
                            error == null ? null : error.code(),
                            error == null ? null : error.message(),
                            id,
                            from.name())
                    .update();
            if (rows == 0) {
                return false;
            }
            int attempt = attempt(id);
            // the attempt itself failed
            jdbc.sql("UPDATE job_run_attempt SET status = ?, ended_at = ?, error_code = ?, error_message = ?"
                            + " WHERE run_id = ? AND attempt = ?")
                    .params(
                            RunState.FAILED.name(),
                            utc(at),
                            error == null ? null : error.code(),
                            error == null ? null : error.message(),
                            id,
                            attempt)
                    .update();
            insertTransition(id, attempt, from, RunState.RETRY_WAIT, at, reason);
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    /** The runs in {@code RETRY_WAIT} whose next attempt is due at {@code now}. */
    public List<Runs.Run> dueRetries(Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM job_run WHERE status = ? AND retry_at <= ? ORDER BY retry_at")
                .params(RunState.RETRY_WAIT.name(), utc(now))
                .query(RUN)
                .list();
    }

    /**
     * Starts attempt {@code attempt} of a run in {@code RETRY_WAIT}: back to {@code QUEUE}.
     *
     * @param restorePath the checkpoint it starts from; {@code null} from the beginning
     * @return whether this call started it
     */
    public boolean startAttempt(String id, int attempt, @Nullable String restorePath, Instant now, String reason) {
        return startAttempt(id, RunState.RETRY_WAIT, attempt, restorePath, now, reason);
    }

    /**
     * Starts attempt {@code attempt} of a run that is in {@code from}, a retry waiting or a run that
     * ended: back to {@code QUEUE}.
     *
     * @param restorePath the checkpoint it starts from; {@code null} from the beginning
     * @return whether this call started it
     */
    public boolean startAttempt(
            String id, RunState from, int attempt, @Nullable String restorePath, Instant now, String reason) {
        Boolean started = transactions.execute(tx -> {
            int rows = jdbc.sql("UPDATE job_run SET status = ?, attempt = ?, retry_at = NULL, updated_at = ?,"
                            + " ended_at = NULL, error_code = NULL, error_message = NULL WHERE id = ? AND status = ?")
                    .params(RunState.QUEUE.name(), attempt, utc(now), id, from.name())
                    .update();
            if (rows == 0) {
                return false;
            }
            jdbc.sql("INSERT INTO job_run_attempt (run_id, attempt, status, started_at, restore_path)"
                            + " VALUES (?, ?, ?, ?, ?)")
                    .params(id, attempt, RunState.QUEUE.name(), utc(now), restorePath)
                    .update();
            insertTransition(id, attempt, from, RunState.QUEUE, now, reason);
            return true;
        });
        return Boolean.TRUE.equals(started);
    }

    /** The directory of the last completed checkpoint of a run, which the master keeps for a failed run. */
    public Optional<String> lastCheckpoint(String runId) {
        return jdbc.sql("SELECT path FROM checkpoint WHERE run_id = ? AND status = 'COMPLETED' AND type = 'CHECKPOINT'"
                        + " ORDER BY checkpoint_id DESC LIMIT 1")
                .param(runId)
                .query(String.class)
                .optional();
    }

    public Optional<Runs.Run> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM job_run WHERE id = ?")
                .param(id)
                .query(RUN)
                .optional();
    }

    /**
     * The run, with its definition and transitions.
     *
     * @throws PipeForgeException {@code PF-CONF-0010} for a run the metastore does not have
     */
    public Runs.RunDetail detail(String id) {
        Runs.Run run = find(id).orElseThrow(() -> notFound(id));
        String definition = jdbc.sql("SELECT job_yaml FROM job_run WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
        List<Runs.Transition> transitions = jdbc.sql(
                        "SELECT attempt, from_status, to_status, occurred_at, reason FROM job_run_transition"
                                + " WHERE run_id = ? ORDER BY id")
                .param(id)
                .query((rs, row) -> {
                    String from = rs.getString("from_status");
                    return new Runs.Transition(
                            rs.getInt("attempt"),
                            from == null ? null : RunState.valueOf(from),
                            RunState.valueOf(rs.getString("to_status")),
                            instant(rs.getObject("occurred_at", LocalDateTime.class)),
                            rs.getString("reason"));
                })
                .list();
        List<Runs.Attempt> attempts = jdbc.sql(
                        "SELECT attempt, status, started_at, ended_at, error_code, error_message, restore_path FROM"
                                + " job_run_attempt WHERE run_id = ? ORDER BY attempt")
                .param(id)
                .query((rs, row) -> {
                    String code = rs.getString("error_code");
                    return new Runs.Attempt(
                            rs.getInt("attempt"),
                            RunState.valueOf(rs.getString("status")),
                            instant(rs.getObject("started_at", LocalDateTime.class)),
                            instant(rs.getObject("ended_at", LocalDateTime.class)),
                            code == null
                                    ? null
                                    : new Runs.RunError(code, String.valueOf(rs.getString("error_message"))),
                            rs.getString("restore_path"));
                })
                .list();
        return new Runs.RunDetail(run, definition, transitions, attempts);
    }

    static PipeForgeException notFound(String id) {
        return PipeForgeException.of(ErrorCode.RUN_NOT_FOUND).param("run", id).build();
    }

    /**
     * Runs newest first, optionally of some states or of one job.
     *
     * @param page the token of the page, from the previous page; {@code null} for the first
     */
    public Runs.RunPage list(Set<RunState> states, @Nullable String job, int limit, @Nullable String page) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM job_run WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (!states.isEmpty()) {
            sql.append(" AND status IN (")
                    .append(String.join(", ", states.stream().map(s -> "?").toList()))
                    .append(')');
            states.stream().map(RunState::name).sorted().forEach(params::add);
        }
        if (job != null && !job.isBlank()) {
            sql.append(" AND job_name = ?");
            params.add(job);
        }
        if (page != null && !page.isBlank()) {
            Cursor cursor = Cursor.decode(page);
            sql.append(" AND (submitted_at < ? OR (submitted_at = ? AND id < ?))");
            params.add(utc(cursor.submittedAt()));
            params.add(utc(cursor.submittedAt()));
            params.add(cursor.id());
        }
        // LIMIT in the query, not a stream: a stream of JdbcClient keeps its connection until closed
        sql.append(" ORDER BY submitted_at DESC, id DESC LIMIT ?");
        params.add(size + 1);
        List<Runs.Run> runs = jdbc.sql(sql.toString()).params(params).query(RUN).list();
        if (runs.size() <= size) {
            return new Runs.RunPage(runs, null);
        }
        List<Runs.Run> shown = runs.subList(0, size);
        Runs.Run last = shown.get(size - 1);
        return new Runs.RunPage(List.copyOf(shown), new Cursor(last.submittedAt(), last.id()).encode());
    }

    /** The runs that have not ended, which the server follows. */
    public List<Runs.Run> live() {
        List<String> states = java.util.Arrays.stream(RunState.values())
                .filter(s -> !s.isTerminal())
                .map(RunState::name)
                .toList();
        return jdbc.sql("SELECT " + COLUMNS + " FROM job_run WHERE status IN ("
                        + String.join(", ", states.stream().map(s -> "?").toList()) + ") ORDER BY submitted_at")
                .params(states)
                .query(RUN)
                .list();
    }

    /**
     * Moves a run from {@code from} to {@code to}, unless it is no longer in {@code from}.
     *
     * @param started when the run started, recorded when it was not yet
     * @param error the error of a run that ends without success
     * @return whether this call made the change
     */
    public boolean transition(
            String id,
            RunState from,
            RunState to,
            Instant at,
            @Nullable Instant started,
            Runs.@Nullable RunError error,
            @Nullable String reason) {
        Boolean changed = transactions.execute(tx -> {
            int rows = jdbc.sql("UPDATE job_run SET status = ?, started_at = COALESCE(started_at, ?), ended_at = ?,"
                            + " updated_at = ?, error_code = ?, error_message = ? WHERE id = ? AND status = ?")
                    .params(
                            to.name(),
                            started == null ? null : utc(started),
                            to.isTerminal() ? utc(at) : null,
                            utc(at),
                            error == null ? null : error.code(),
                            error == null ? null : error.message(),
                            id,
                            from.name())
                    .update();
            if (rows == 0) {
                return false;
            }
            int attempt = attempt(id);
            jdbc.sql("UPDATE job_run_attempt SET status = ?, ended_at = ?, error_code = ?, error_message = ?"
                            + " WHERE run_id = ? AND attempt = ?")
                    .params(
                            to.name(),
                            to.isTerminal() ? utc(at) : null,
                            error == null ? null : error.code(),
                            error == null ? null : error.message(),
                            id,
                            attempt)
                    .update();
            insertTransition(id, attempt, from, to, at, reason);
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    private void insertTransition(
            String id, int attempt, @Nullable RunState from, RunState to, Instant at, @Nullable String reason) {
        jdbc.sql("INSERT INTO job_run_transition (run_id, attempt, from_status, to_status, occurred_at, reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, attempt, from == null ? null : from.name(), to.name(), utc(at), reason)
                .update();
    }

    /** Keeps a sample of a run's metrics; a sample another server took at the same moment is kept once. */
    public void sample(String runId, RunMetrics metrics, String json) {
        sample(runId, 1, metrics, json);
    }

    /**
     * Keeps a sample of the metrics of attempt {@code attempt}: its counters, the counters of the
     * attempts before added, and the attempt's own metrics as JSON.
     */
    public void sample(String runId, int attempt, RunMetrics metrics, String json) {
        RunMetrics.Sample before = attempt > 1 ? countsBefore(runId, attempt) : null;
        try {
            jdbc.sql("INSERT INTO job_run_metrics (run_id, sampled_at, elapsed_ms, rows_read, rows_written, rows_dirty,"
                            + " rows_failed, metrics_json, attempt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            runId,
                            utc(metrics.at()),
                            metrics.elapsedMs() + (before == null ? 0 : before.elapsedMs()),
                            metrics.rowsRead() + (before == null ? 0 : before.rowsRead()),
                            metrics.rowsWritten() + (before == null ? 0 : before.rowsWritten()),
                            metrics.rowsDirty() + (before == null ? 0 : before.rowsDirty()),
                            metrics.rowsFailed() + (before == null ? 0 : before.rowsFailed()),
                            json,
                            attempt)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // taken already
        }
    }

    /**
     * The counters of a run up to the end of the attempts before {@code attempt}: its last sample of
     * an earlier attempt, whose counters are those of all attempts up to it.
     */
    public RunMetrics.@Nullable Sample countsBefore(String runId, int attempt) {
        return jdbc.sql("SELECT sampled_at, elapsed_ms, rows_read, rows_written, rows_dirty, rows_failed FROM"
                        + " job_run_metrics WHERE run_id = ? AND COALESCE(attempt, 1) < ? ORDER BY sampled_at DESC"
                        + " LIMIT 1")
                .params(runId, attempt)
                .query((rs, row) -> new RunMetrics.Sample(
                        instant(rs.getObject("sampled_at", LocalDateTime.class)),
                        rs.getLong("elapsed_ms"),
                        rs.getLong("rows_read"),
                        rs.getLong("rows_written"),
                        rs.getLong("rows_dirty"),
                        rs.getLong("rows_failed")))
                .optional()
                .orElse(null);
    }

    /** The full metrics of the last sample of a run, as JSON. */
    public Optional<String> latestMetrics(String runId) {
        return jdbc.sql("SELECT metrics_json FROM job_run_metrics WHERE run_id = ? ORDER BY sampled_at DESC LIMIT 1")
                .param(runId)
                .query(String.class)
                .optional();
    }

    /** The samples of a run, oldest first, from {@code since} on, at most {@code limit}. */
    public List<RunMetrics.Sample> samples(String runId, @Nullable Instant since, int limit) {
        return jdbc.sql("SELECT sampled_at, elapsed_ms, rows_read, rows_written, rows_dirty, rows_failed FROM"
                        + " job_run_metrics WHERE run_id = ? AND sampled_at >= ? ORDER BY sampled_at LIMIT ?")
                .params(runId, utc(since == null ? Instant.EPOCH : since), Math.max(1, Math.min(limit, 10_000)))
                .query((rs, row) -> new RunMetrics.Sample(
                        instant(rs.getObject("sampled_at", LocalDateTime.class)),
                        rs.getLong("elapsed_ms"),
                        rs.getLong("rows_read"),
                        rs.getLong("rows_written"),
                        rs.getLong("rows_dirty"),
                        rs.getLong("rows_failed")))
                .list();
    }

    /** Removes the samples taken before {@code before}; returns how many. */
    public int deleteSamplesBefore(Instant before) {
        return jdbc.sql("DELETE FROM job_run_metrics WHERE sampled_at < ?")
                .param(utc(before))
                .update();
    }

    /** Keeps the run analysis of a run that ended. */
    public void saveAnalysis(String runId, String json) {
        jdbc.sql("UPDATE job_run SET analysis_json = ? WHERE id = ?")
                .params(json, runId)
                .update();
    }

    public Optional<String> analysis(String runId) {
        return jdbc.sql("SELECT analysis_json FROM job_run WHERE id = ? AND analysis_json IS NOT NULL")
                .param(runId)
                .query(String.class)
                .optional();
    }

    /** Where a page ends: the submission time and ID of its last run. */
    record Cursor(Instant submittedAt, String id) {
        String encode() {
            String text = submittedAt.toEpochMilli() + ":" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String token) {
            try {
                String text = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                int colon = text.indexOf(':');
                return new Cursor(
                        Instant.ofEpochMilli(Long.parseLong(text.substring(0, colon))), text.substring(colon + 1));
            } catch (RuntimeException e) {
                throw PipeForgeException.of(ErrorCode.REQUEST_INVALID)
                        .param("reason", "page is not a token of a page of runs")
                        .build();
            }
        }
    }
}
