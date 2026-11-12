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
@Command(name = "run", mixinStandardHelpOptions = true, sortOptions = false)
public final class RunCommand extends BaseCommand {

    @Parameters(paramLabel = "<file>", descriptionKey = "run.file")
    Path file;

    @Option(names = "--local", descriptionKey = "run.local")
    boolean local;

    @Option(names = "--dry-run", descriptionKey = "run.dry-run")
    boolean dryRun;

    @Option(names = "--analyze", descriptionKey = "run.analyze")
    boolean analyze;

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
        if (!local && !dryRun) {
            throw new ParameterException(spec.commandLine(), session.messages().get("run.cluster"));
        }
        Path path = JobFiles.expand(session, List.of(file)).get(0);
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
            try (LocalEngine engine = new LocalEngine()) {
                return run(session, engine, plan);
            }
        } finally {
            if (components[0] != null) {
                components[0].close();
            }
        }
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
