/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Failure;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.common.error.ErrorInfo;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.explain.PlanExplainer;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PluginComponents;
import org.devlive.pipeforge.engine.api.AnalyzeText;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.local.LocalEngine;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;

/**
 * {@code pipeforge run --local}: runs a job file in this process and waits for its end. Ctrl-C
 * cancels the job. Running on a cluster arrives with the distributed runtime.
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        subcommands = {
            RunClusterCommands.Status.class,
            RunClusterCommands.Watch.class,
            RunClusterCommands.Logs.class,
            RunClusterCommands.Metrics.class,
            RunClusterCommands.Analyze.class,
            RunClusterCommands.Cancel.class,
            RunRecoveryCommands.Pause.class,
            RunRecoveryCommands.Resume.class,
            RunRecoveryCommands.Savepoint.class,
            RunRecoveryCommands.Retry.class,
            RunRecoveryCommands.Checkpoints.class,
            RunRecoveryCommands.Attempts.class
        })
public final class RunCommand extends BaseCommand {

    // optional for picocli, so that the subcommands need no file; required by execute()
    @Parameters(paramLabel = "<file>", descriptionKey = "run.file", arity = "0..1")
    @Nullable
    Path file;

    @Option(names = "--local", descriptionKey = "run.local")
    boolean local;

    @Option(names = "--workers", paramLabel = "<n>", descriptionKey = "run.workers", converter = WorkersConverter.class)
    @Nullable
    Integer workers;

    @Option(names = "--dry-run", descriptionKey = "run.dry-run")
    boolean dryRun;

    @Option(names = "--analyze", descriptionKey = "run.analyze")
    boolean analyze;

    @Option(names = "--detach", descriptionKey = "run.detach")
    boolean detach;

    @Option(
            names = "--timeout",
            paramLabel = "<duration>",
            descriptionKey = "run.timeout",
            converter = DurationConverter.class)
    @Nullable
    Duration timeout;

    @Mixin
    JobOptions job = new JobOptions();

    /**
     * The outcome of a run.
     *
     * @param reconciled whether the row counters satisfy the reconciliation identity
     */
    public record Result(
            String job,
            JobStatus status,
            long elapsedMs,
            boolean reconciled,
            JobMetrics metrics,
            @Nullable ErrorInfo error) {}

    /** A number of workers: 1 or more. */
    static final class WorkersConverter implements picocli.CommandLine.ITypeConverter<Integer> {
        @Override
        public Integer convert(String value) {
            int count;
            try {
                count = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                count = 0;
            }
            if (count < 1 || count > 64) {
                throw new picocli.CommandLine.TypeConversionException(
                        "'" + value + "' is not a number of workers from 1 to 64");
            }
            return count;
        }
    }

    /** Durations such as 30s, 5m, 2h or 500ms. */
    static final class DurationConverter implements picocli.CommandLine.ITypeConverter<Duration> {
        @Override
        public Duration convert(String value) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(ms|s|m|h)")
                    .matcher(value.trim().toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                throw new picocli.CommandLine.TypeConversionException(
                        "'" + value + "' is not a duration such as 500ms, 30s, 5m or 2h");
            }
            long amount = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                default -> Duration.ofHours(amount);
            };
        }
    }

    @Override
    protected ExitCode execute(Session session) throws Exception {
        if (file == null) {
            throw new ParameterException(spec.commandLine(), session.messages().get("run.file-missing"));
        }
        if (!local && workers != null) {
            throw new ParameterException(spec.commandLine(), session.messages().get("run.workers-local"));
        }
        if (local && detach) {
            throw new ParameterException(spec.commandLine(), session.messages().get("run.detach-local"));
        }
        Path path = JobFiles.expand(session, List.of(file)).get(0);
        if (!local && !dryRun) {
            return cluster(session, JobFiles.read(path));
        }
        PluginComponents[] components = new PluginComponents[1];
        try {
            JobPlan plan;
            try {
                plan = job.plan(session, JobFiles.read(path), components);
            } catch (PipeForgeException e) {
                throw new Failure(e, ExitCode.INVALID);
            }
            if (dryRun) {
                String text = PlanExplainer.text(plan);
                session.print(
                        new ExplainCommand.Result(PlanExplainer.document(plan), text),
                        text.endsWith("\n") ? text : text + "\n");
                return ExitCode.SUCCESS;
            }
            try (LocalEngine engine = workers == null ? new LocalEngine() : new LocalEngine(workers)) {
                return run(session, engine, plan);
            }
        } finally {
            if (components[0] != null) {
                components[0].close();
            }
        }
    }

    /**
     * What {@code -o json} prints for a run of the cluster.
     *
     * @param analysis the run analysis, with --analyze
     */
    public record ClusterResult(
            org.devlive.pipeforge.client.Run run,
            org.devlive.pipeforge.client.@Nullable RunMetrics metrics,
            com.fasterxml.jackson.databind.@Nullable JsonNode analysis) {}

    /**
     * Submits the job to the server and, unless detached, follows the run until it ends: progress
     * on a terminal, then the summary; the exit code says how it ended. With --timeout the run is
     * cancelled when the time is up. Interrupting the command leaves the run running.
     */
    private ExitCode cluster(Session session, String definition) throws Exception {
        org.devlive.pipeforge.client.PipeForgeClient client = session.client();
        org.devlive.pipeforge.client.Run submitted = client.submitRun(definition, job.variables, job.scheduledTime);
        if (detach) {
            session.print(new ClusterResult(submitted, null, null), submitted.id() + "\n");
            return ExitCode.SUCCESS;
        }
        session.note(session.messages().get("run.submitted", submitted.id(), client.server()));
        boolean progress =
                session.table() && !session.quiet() && session.environment().interactive();
        long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        boolean timedOut = false;
        org.devlive.pipeforge.client.RunDetail detail;
        try {
            while (true) {
                detail = client.run(submitted.id());
                if (org.devlive.pipeforge.client.PipeForgeClient.ENDED.contains(
                        detail.run().status())) {
                    break;
                }
                if (!timedOut && System.nanoTime() - deadline >= 0) {
                    timedOut = true;
                    client.cancelRun(submitted.id(), "--timeout " + timeout);
                }
                if (progress) {
                    org.devlive.pipeforge.client.RunMetrics now = client.metrics(submitted.id());
                    session.err()
                            .print("\r"
                                    + session.messages()
                                            .get(
                                                    "run.progress",
                                                    RunViews.duration(
                                                            RunViews.elapsed(detail.run(), java.time.Instant.now())),
                                                    RunViews.count(now.rowsRead()),
                                                    RunViews.count(now.rowsWritten()),
                                                    RunViews.rate(now.writeRowsPerSecond())));
                    session.err().flush();
                }
                Thread.sleep(1000);
            }
        } finally {
            if (progress) {
                session.err().print("\r\u001b[2K");
                session.err().flush();
            }
        }
        org.devlive.pipeforge.client.Run run = detail.run();
        org.devlive.pipeforge.client.RunMetrics metrics = client.metrics(run.id());
        com.fasterxml.jackson.databind.JsonNode analysis =
                analyze ? RunClusterCommands.analysis(client, run.id()) : null;
        StringBuilder text = new StringBuilder();
        long elapsed = RunViews.elapsed(run, java.time.Instant.now());
        text.append(session.messages()
                        .get(
                                "run.summary",
                                run.job(),
                                run.status(),
                                RunViews.duration(elapsed),
                                RunViews.count(metrics.rowsRead()),
                                RunViews.count(metrics.rowsWritten()),
                                RunViews.rate(elapsed > 0 ? metrics.rowsWritten() * 1000.0 / elapsed : 0)))
                .append('\n');
        if (timedOut) {
            text.append(session.messages().get("run.timeout", timeout)).append('\n');
        }
        if (run.error() != null) {
            text.append("ERROR ")
                    .append(run.error().code())
                    .append(' ')
                    .append(run.error().message())
                    .append('\n');
        }
        if (analysis != null) {
            String analysisText = org.devlive.pipeforge.engine.api.RunAnalysis.fromJson(analysis.toString())
                    .text();
            text.append('\n').append(analysisText);
            if (!analysisText.endsWith("\n")) {
                text.append('\n');
            }
        }
        session.print(new ClusterResult(run, metrics, analysis), text.toString());
        return timedOut ? ExitCode.TIMEOUT : RunViews.exit(run.status());
    }

    private ExitCode run(Session session, LocalEngine engine, JobPlan plan) throws Exception {
        JobHandle handle = engine.submit(plan, SubmitOptions.defaults());
        Thread cancel = new Thread(() -> {
            handle.cancel();
            try {
                handle.result().get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                // The JVM is ending anyway.
            }
        });
        Runtime.getRuntime().addShutdownHook(cancel);
        boolean progress =
                session.table() && !session.quiet() && session.environment().interactive();
        long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        JobResult result = null;
        boolean timedOut = false;
        try {
            while (result == null) {
                long wait = Math.min(TimeUnit.SECONDS.toNanos(1), deadline - System.nanoTime());
                if (wait <= 0) {
                    timedOut = true;
                    handle.cancel();
                    result = handle.result().get(60, TimeUnit.SECONDS);
                    break;
                }
                try {
                    result = handle.result().get(wait, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    if (progress) {
                        session.err().print("\r" + progress(session, handle.metrics()));
                        session.err().flush();
                    }
                }
            }
        } finally {
            removeHook(cancel);
            if (progress) {
                session.err().print("\r\u001b[2K");
                session.err().flush();
            }
        }
        JobMetrics metrics = result.metrics();
        ErrorInfo error = result.error() == null ? null : result.error().toErrorInfo(session.language());
        Result outcome =
                new Result(plan.name(), result.status(), metrics.elapsedMs(), metrics.reconciled(), metrics, error);
        session.print(outcome, table(session, plan.name(), result, timedOut));
        if (timedOut) {
            return ExitCode.TIMEOUT;
        }
        return switch (result.status()) {
            case SUCCESS -> metrics.reconciled() ? ExitCode.SUCCESS : ExitCode.NOT_RECONCILED;
            case CANCELLED -> ExitCode.CANCELLED;
            default -> ExitCode.RUN_FAILED;
        };
    }

    private static void removeHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // Shutting down: the hook runs.
        }
    }

    private static String progress(Session session, JobMetrics metrics) {
        return session.messages()
                .get(
                        "run.progress",
                        seconds(metrics.elapsedMs()),
                        String.format(Locale.ROOT, "%,d", metrics.rowsRead()),
                        String.format(Locale.ROOT, "%,d", metrics.rowsWritten()),
                        String.format(
                                Locale.ROOT,
                                "%,.0f",
                                metrics.writeRowsPerSecond().current()));
    }

    private String table(Session session, String name, JobResult result, boolean timedOut) {
        JobMetrics m = result.metrics();
        StringBuilder text = new StringBuilder();
        text.append(session.messages()
                        .get(
                                "run.summary",
                                name,
                                result.status(),
                                seconds(m.elapsedMs()),
                                String.format(Locale.ROOT, "%,d", m.rowsRead()),
                                String.format(Locale.ROOT, "%,d", m.rowsWritten()),
                                String.format(
                                        Locale.ROOT,
                                        "%,.0f",
                                        m.writeRowsPerSecond().average())))
                .append('\n');
        if (timedOut) {
            text.append(session.messages().get("run.timeout", timeout)).append('\n');
        }
        if (result.status() == JobStatus.SUCCESS && !m.reconciled()) {
            text.append(session.messages().get("run.not-reconciled")).append('\n');
        }
        if (result.error() != null) {
            ErrorInfo error = result.error().toErrorInfo(session.language());
            text.append("ERROR ")
                    .append(error.code())
                    .append(' ')
                    .append(error.message())
                    .append('\n');
            if (error.hint() != null && !error.hint().isBlank()) {
                text.append("  ").append(error.hint()).append('\n');
            }
        }
        if (analyze) {
            text.append('\n').append(AnalyzeText.format(name, result.status(), m));
            if (text.charAt(text.length() - 1) != '\n') {
                text.append('\n');
            }
        }
        return text.toString();
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }
}
