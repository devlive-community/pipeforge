/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.server.config.JobPlanning;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Submits runs to the master and records them in the metastore. A job is planned on the server
 * first, so that an invalid job is refused before it becomes a run, and the master plans it again
 * with the plugin versions the server chose.
 */
@Service
public class RunService {

    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);

    private final JobPlanning planning;
    private final RunRepository runs;
    private final MasterClient master;
    private final Clock clock;
    private final tools.jackson.databind.json.JsonMapper json;

    public RunService(
            JobPlanning planning,
            RunRepository runs,
            MasterClient master,
            Clock clock,
            tools.jackson.databind.json.JsonMapper json) {
        this.planning = planning;
        this.runs = runs;
        this.master = master;
        this.clock = clock;
        this.json = json;
    }

    private Runs.Run run(String id) {
        return runs.find(id).orElseThrow(() -> RunRepository.notFound(id));
    }

    /**
     * The metrics of a run: from the master while it runs, else the last sample the server kept.
     *
     * @throws PipeForgeException {@code PF-CONF-0010} for an unknown run
     */
    public RunMetrics metrics(String id) {
        Runs.Run run = run(id);
        if (!run.status().isTerminal()) {
            try {
                java.util.Optional<RunMetrics> live = master.metrics(id);
                if (live.isPresent()) {
                    return live.get();
                }
            } catch (PipeForgeException e) {
                LOG.debug("No live metrics of run {}: {}", id, e.getMessage());
            }
        }
        return runs.latestMetrics(id)
                .map(text -> json.readValue(text, RunMetrics.class))
                .orElseGet(() -> RunMetrics.none(run.endedAt() != null ? run.endedAt() : run.updatedAt()));
    }

    /** The samples of a run's metrics, oldest first. */
    public java.util.List<RunMetrics.Sample> history(String id, @Nullable Instant since, int limit) {
        run(id);
        return runs.samples(id, since, limit);
    }

    /**
     * The run analysis (design 29): kept when the run ended, else from the master.
     *
     * @throws PipeForgeException {@code PF-CONF-0010} for an unknown run, or when neither the
     *     server nor the master has its analysis
     */
    public tools.jackson.databind.JsonNode analysis(String id) {
        run(id);
        String text = runs.analysis(id).or(() -> master.analysis(id)).orElseThrow(() -> RunRepository.notFound(id));
        return json.readTree(text);
    }

    /** The splits of a run with their readers; empty when the master no longer has the run. */
    public java.util.List<RunMetrics.Split> splits(String id) {
        run(id);
        return master.splits(id).orElse(java.util.List.of());
    }

    /** The tasks of a run with their workers; empty when the master no longer has the run. */
    public java.util.List<RunMetrics.Task> tasks(String id) {
        run(id);
        return master.status(id).map(MasterClient.Status::tasks).orElse(java.util.List.of());
    }

    /**
     * Reads a part of a task's log through the master.
     *
     * @throws PipeForgeException {@code PF-CLUS-0012} when there is no such log
     */
    public RunMetrics.LogChunk log(String id, String vertex, int subtask, int attempt, MasterClient.LogQuery query) {
        run(id);
        return master.readLog(id, new RunMetrics.Task(vertex, subtask, attempt, "", ""), query);
    }

    /**
     * Plans the job, records the run and submits it.
     *
     * @throws PipeForgeException what planning reports for an invalid job (no run is recorded), or
     *     why the master did not take the run (the run is recorded as failed)
     */
    public Runs.Run submit(
            String definition, @Nullable Map<String, String> variables, @Nullable Instant scheduledTime) {
        Instant now = clock.instant();
        Instant scheduled = scheduledTime != null ? scheduledTime : now;
        Map<String, String> values = variables == null ? Map.of() : Map.copyOf(variables);
        JobPlan plan = planning.plan(definition, values, scheduled);
        Map<String, String> plugins = new LinkedHashMap<>();
        for (PlannedNode node : plan.nodes()) {
            String coordinate = node.plugin();
            int at = coordinate.lastIndexOf('@');
            if (at > 0) {
                plugins.put(coordinate.substring(0, at), coordinate.substring(at + 1));
            }
        }
        String id = UUID.randomUUID().toString();
        Runs.Run run = runs.insert(id, plan.name(), definition, null, now);
        try {
            master.submit(new MasterClient.Submission(plan.name(), id, definition, values, scheduled, plugins));
        } catch (PipeForgeException e) {
            LOG.warn("The master did not take run {} of job {}: {}", id, plan.name(), e.getMessage());
            runs.transition(
                    id,
                    RunState.QUEUE,
                    RunState.FAILED,
                    clock.instant(),
                    null,
                    new Runs.RunError(e.code().id(), e.getMessage()),
                    e.code().id());
            throw e;
        }
        LOG.info("Submitted run {} of job {} to {}", id, plan.name(), master.address());
        return run;
    }

    /**
     * Asks the master to cancel a run that has not ended; an ended run is returned as it is.
     *
     * @throws PipeForgeException {@code PF-CONF-0010} for an unknown run
     */
    public Runs.Run cancel(String id, @Nullable String reason) {
        Runs.Run run = runs.find(id).orElseThrow(() -> RunRepository.notFound(id));
        if (run.status().isTerminal() || run.status() == RunState.CANCELLING) {
            return run;
        }
        String why = reason == null || reason.isBlank() ? "cancelled through the API" : reason;
        master.cancel(id, why);
        runs.transition(id, run.status(), RunState.CANCELLING, clock.instant(), null, null, why);
        return runs.find(id).orElseThrow();
    }
}
