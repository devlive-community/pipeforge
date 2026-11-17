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
                    + LAST_SAMPLE + " AS rows_written";

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
                rs.getObject("rows_written", Long.class));
    };

    /** Records a submitted run in {@code QUEUE}, with its first attempt and its first transition. */
    public Runs.Run insert(String id, String job, String definition, @Nullable String submittedBy, Instant now) {
        transactions.executeWithoutResult(tx -> {
            jdbc.sql("INSERT INTO job_run (id, job_name, job_yaml, status, submitted_by, submitted_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(id, job, definition, RunState.QUEUE.name(), submittedBy, utc(now), utc(now))
                    .update();
            jdbc.sql("INSERT INTO job_run_attempt (run_id, attempt, status, started_at) VALUES (?, 1, ?, ?)")
                    .params(id, RunState.QUEUE.name(), utc(now))
                    .update();
            insertTransition(id, null, RunState.QUEUE, now, "submitted");
        });
        return find(id).orElseThrow();
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
        return new Runs.RunDetail(run, definition, transitions);
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
        sql.append(" ORDER BY submitted_at DESC, id DESC");
        List<Runs.Run> runs = jdbc.sql(sql.toString()).params(params).query(RUN).stream()
                .limit(size + 1L)
                .toList();
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
            jdbc.sql("UPDATE job_run_attempt SET status = ?, ended_at = ?, error_code = ?, error_message = ?"
                            + " WHERE run_id = ? AND attempt = 1")
                    .params(
                            to.name(),
                            to.isTerminal() ? utc(at) : null,
                            error == null ? null : error.code(),
                            error == null ? null : error.message(),
                            id)
                    .update();
            insertTransition(id, from, to, at, reason);
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    private void insertTransition(
            String id, @Nullable RunState from, RunState to, Instant at, @Nullable String reason) {
        jdbc.sql("INSERT INTO job_run_transition (run_id, attempt, from_status, to_status, occurred_at, reason)"
                        + " VALUES (?, 1, ?, ?, ?, ?)")
                .params(id, from == null ? null : from.name(), to.name(), utc(at), reason)
                .update();
    }

    /** Keeps a sample of a run's metrics; a sample another server took at the same moment is kept once. */
    public void sample(String runId, RunMetrics metrics, String json) {
        try {
            jdbc.sql("INSERT INTO job_run_metrics (run_id, sampled_at, elapsed_ms, rows_read, rows_written, rows_dirty,"
                            + " rows_failed, metrics_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            runId,
                            utc(metrics.at()),
                            metrics.elapsedMs(),
                            metrics.rowsRead(),
                            metrics.rowsWritten(),
                            metrics.rowsDirty(),
                            metrics.rowsFailed(),
                            json)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // taken already
        }
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
        return jdbc
                .sql("SELECT sampled_at, elapsed_ms, rows_read, rows_written, rows_dirty, rows_failed FROM"
                        + " job_run_metrics WHERE run_id = ? AND sampled_at >= ? ORDER BY sampled_at")
                .params(runId, utc(since == null ? Instant.EPOCH : since))
                .query((rs, row) -> new RunMetrics.Sample(
                        instant(rs.getObject("sampled_at", LocalDateTime.class)),
                        rs.getLong("elapsed_ms"),
                        rs.getLong("rows_read"),
                        rs.getLong("rows_written"),
                        rs.getLong("rows_dirty"),
                        rs.getLong("rows_failed")))
                .stream()
                .limit(Math.max(1, Math.min(limit, 10_000)))
                .toList();
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
