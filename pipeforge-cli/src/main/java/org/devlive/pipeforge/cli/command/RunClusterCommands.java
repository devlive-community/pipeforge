/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.client.ApiException;
import org.devlive.pipeforge.client.LogQuery;
import org.devlive.pipeforge.client.PipeForgeClient;
import org.devlive.pipeforge.client.Run;
import org.devlive.pipeforge.client.RunDetail;
import org.devlive.pipeforge.client.RunMetrics;
import org.devlive.pipeforge.client.RunTask;
import org.devlive.pipeforge.engine.api.RunAnalysis;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;

/** The commands about one run of the cluster: {@code run status | watch | logs | metrics | analyze | cancel}. */
public final class RunClusterCommands {

    private RunClusterCommands() {}

    /** The run analysis, or nothing when the server has none (yet). */
    static @Nullable JsonNode analysis(PipeForgeClient client, String id) {
        try {
            return client.analysis(id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    /** The status of a run: state, progress, rows and rates, the bottleneck and the error. */
    @Command(name = "status", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Status extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        /** What {@code -o json} prints. */
        public record Result(Run run, RunMetrics metrics, @Nullable JsonNode analysis) {}

        @Override
        protected ExitCode execute(Session session) {
            PipeForgeClient client = session.client();
            RunDetail detail = client.run(id);
            RunMetrics metrics = client.metrics(id);
            JsonNode analysis = analysis(client, id);
            session.print(
                    new Result(detail.run(), metrics, analysis),
                    RunViews.status(session.messages(), detail.run(), metrics, analysis, Instant.now()));
            return ExitCode.SUCCESS;
        }
    }

    /**
     * Follows a run until it ends: on a terminal a view redrawn every interval, else one JSON line
     * per interval. Exits as the run ended.
     */
    @Command(name = "watch", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Watch extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @Option(
                names = "--interval",
                paramLabel = "<duration>",
                descriptionKey = "run.watch.interval",
                converter = RunCommand.DurationConverter.class)
        Duration interval = Duration.ofSeconds(2);

        static final com.fasterxml.jackson.databind.ObjectMapper LINES =
                new com.fasterxml.jackson.databind.ObjectMapper();

        @Override
        protected ExitCode execute(Session session) throws Exception {
            PipeForgeClient client = session.client();
            boolean screen = session.table() && session.environment().interactive();
            int drawn = 0;
            while (true) {
                RunDetail detail = client.run(id);
                RunMetrics metrics = client.metrics(id);
                Run run = detail.run();
                boolean ended = PipeForgeClient.ENDED.contains(run.status());
                if (screen) {
                    JsonNode analysis = analysis(client, id);
                    String frame = RunViews.status(session.messages(), run, metrics, analysis, Instant.now())
                            + "\n"
                            + RunViews.nodes(session.messages(), metrics.nodes());
                    // move up over the last frame and draw over it
                    session.out().print((drawn > 0 ? "\u001b[" + drawn + "F\u001b[J" : "") + frame);
                    session.out().flush();
                    drawn = (int) frame.chars().filter(c -> c == '\n').count();
                } else {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("at", Instant.now().toString());
                    line.put("run", run.id());
                    line.put("status", run.status());
                    line.put("rowsRead", metrics.rowsRead());
                    line.put("rowsWritten", metrics.rowsWritten());
                    line.put("readRowsPerSecond", metrics.readRowsPerSecond());
                    line.put("writeRowsPerSecond", metrics.writeRowsPerSecond());
                    line.put("splitsFinished", metrics.splitsFinished());
                    line.put("splitsTotal", metrics.splitsTotal());
                    session.out().println(LINES.writeValueAsString(line));
                    session.out().flush();
                }
                if (ended) {
                    if (run.error() != null) {
                        session.err()
                                .println("ERROR " + run.error().code() + " "
                                        + run.error().message());
                    }
                    return RunViews.exit(run.status());
                }
                Thread.sleep(interval.toMillis());
            }
        }
    }

    /** A task given as vertex[subtask], e.g. src[3]; the subtask is 0 when left out. */
    record TaskRef(String vertex, int subtask) {
        static final Pattern FORM = Pattern.compile("([^\\[\\]]+)(?:\\[(\\d+)])?");

        static TaskRef parse(Session session, String text) {
            Matcher matcher = FORM.matcher(text.strip());
            if (!matcher.matches()) {
                throw new ParameterException(
                        new picocli.CommandLine(new Object()),
                        session.messages().get("run.logs.task-form", text));
            }
            return new TaskRef(matcher.group(1), matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)));
        }

        boolean matches(RunTask task) {
            return task.vertex().equals(vertex) && task.subtask() == subtask;
        }
    }

    /** The logs of the tasks of a run, read from their workers through the server. */
    @Command(name = "logs", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Logs extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @Option(names = "--task", paramLabel = "<vertex[subtask]>", descriptionKey = "run.logs.task")
        @Nullable
        String task;

        @Option(
                names = {"-f", "--follow"},
                descriptionKey = "run.logs.follow")
        boolean follow;

        @Option(names = "--level", paramLabel = "<level>", descriptionKey = "run.logs.level")
        @Nullable
        String level;

        @Option(names = "--grep", paramLabel = "<text>", descriptionKey = "run.logs.grep")
        String grep = "";

        @Override
        protected ExitCode execute(Session session) throws InterruptedException {
            if (level != null
                    && !List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR")
                            .contains(level.toUpperCase(java.util.Locale.ROOT))) {
                throw new ParameterException(
                        spec.commandLine(), session.messages().get("run.logs.level-invalid", level));
            }
            PipeForgeClient client = session.client();
            List<RunTask> tasks = client.tasks(id);
            List<RunTask> chosen;
            if (task != null) {
                TaskRef ref = TaskRef.parse(session, task);
                // the latest attempt of the task
                chosen = tasks.stream()
                        .filter(ref::matches)
                        .max(java.util.Comparator.comparingInt(RunTask::attempt))
                        .map(List::of)
                        .orElse(List.of());
                if (chosen.isEmpty()) {
                    throw new ParameterException(
                            spec.commandLine(), session.messages().get("run.logs.no-task", task, names(tasks)));
                }
            } else {
                chosen = tasks;
            }
            if (follow && chosen.size() != 1) {
                throw new ParameterException(
                        spec.commandLine(), session.messages().get("run.logs.follow-one", names(tasks)));
            }
            for (RunTask each : chosen) {
                if (chosen.size() > 1) {
                    session.out()
                            .println(session.messages()
                                    .get(
                                            "run.logs.header",
                                            each.vertex(),
                                            each.subtask(),
                                            String.valueOf(each.worker())));
                }
                LogQuery query = LogQuery.of(each.vertex(), each.subtask(), each.attempt())
                        .withContains(grep)
                        .withLevel(level == null ? null : level.toUpperCase(java.util.Locale.ROOT));
                if (follow) {
                    client.followLog(
                            id,
                            query,
                            Duration.ofSeconds(1),
                            text -> {
                                session.out().print(text);
                                session.out().flush();
                            },
                            chunk -> true);
                } else {
                    // the whole log as it is now: read until a read brings nothing new
                    long offset = 0;
                    while (true) {
                        var chunk = client.readLog(id, query.withOffset(offset));
                        session.out().print(chunk.text());
                        if (chunk.eof() || chunk.offset() == offset) {
                            break;
                        }
                        offset = chunk.offset();
                    }
                }
                session.out().flush();
            }
            return ExitCode.SUCCESS;
        }

        static String names(List<RunTask> tasks) {
            return String.join(
                    ", ",
                    tasks.stream()
                            .map(t -> t.vertex() + "[" + t.subtask() + "]")
                            .distinct()
                            .toList());
        }
    }

    /** The metrics of a run and of each of its nodes. */
    @Command(name = "metrics", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Metrics extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @Override
        protected ExitCode execute(Session session) {
            RunMetrics metrics = session.client().metrics(id);
            List<String[]> rows = new java.util.ArrayList<>();
            rows.add(new String[] {
                session.messages().get("run.metrics.name"), session.messages().get("run.metrics.value")
            });
            rows.add(new String[] {"elapsed", RunViews.duration(metrics.elapsedMs())});
            rows.add(new String[] {"parallelism", String.valueOf(metrics.parallelism())});
            rows.add(new String[] {"rowsRead", RunViews.count(metrics.rowsRead())});
            rows.add(new String[] {"rowsWritten", RunViews.count(metrics.rowsWritten())});
            rows.add(new String[] {"rowsInFlight", RunViews.count(metrics.rowsInFlight())});
            rows.add(new String[] {"rowsDirty", RunViews.count(metrics.rowsDirty())});
            rows.add(new String[] {"readRowsPerSecond", RunViews.rate(metrics.readRowsPerSecond())});
            rows.add(new String[] {"writeRowsPerSecond", RunViews.rate(metrics.writeRowsPerSecond())});
            rows.add(new String[] {"splits", metrics.splitsFinished() + " / " + metrics.splitsTotal()});
            rows.add(new String[] {"readerWait", RunViews.duration(metrics.readerWaitMs())});
            rows.add(new String[] {"writerWait", RunViews.duration(metrics.writerWaitMs())});
            rows.add(new String[] {"throttleWait", RunViews.duration(metrics.throttleWaitMs())});
            rows.add(new String[] {"backpressured", RunViews.duration(metrics.backpressuredMs())});
            session.print(metrics, RunViews.table(rows) + "\n" + RunViews.nodes(session.messages(), metrics.nodes()));
            return ExitCode.SUCCESS;
        }
    }

    /** The run analysis: rates, waits, the bottleneck and what to change. */
    @Command(name = "analyze", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Analyze extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @Override
        protected ExitCode execute(Session session) {
            JsonNode json = session.client().analysis(id);
            String text = RunAnalysis.fromJson(json.toString()).text();
            session.print(json, text.endsWith("\n") ? text : text + "\n");
            return ExitCode.SUCCESS;
        }
    }

    /** Asks a run to stop. */
    @Command(name = "cancel", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Cancel extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @Option(names = "--reason", paramLabel = "<text>", descriptionKey = "run.cancel.reason")
        @Nullable
        String reason;

        @Option(
                names = {"-y", "--yes"},
                descriptionKey = "run.cancel.yes")
        boolean yes;

        // on JDK 21 the console is null without a terminal; interactive() is checked first anyway
        @SuppressWarnings("SystemConsoleNull")
        @Override
        protected ExitCode execute(Session session) {
            if (!yes) {
                java.io.Console console = System.console();
                if (!session.environment().interactive() || console == null) {
                    throw new ParameterException(
                            spec.commandLine(), session.messages().get("run.cancel.confirm-flag", id));
                }
                String answer = console.readLine("%s", session.messages().get("run.cancel.confirm", id));
                if (answer == null
                        || !answer.strip().toLowerCase(java.util.Locale.ROOT).startsWith("y")) {
                    session.note(session.messages().get("run.cancel.kept", id));
                    return ExitCode.SUCCESS;
                }
            }
            Run run = session.client().cancelRun(id, reason);
            session.print(run, session.messages().get("run.cancel.done", run.id(), run.status()) + "\n");
            return ExitCode.SUCCESS;
        }
    }
}
