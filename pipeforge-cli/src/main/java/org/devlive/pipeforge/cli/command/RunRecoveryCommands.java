/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Messages;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.client.Run;
import org.devlive.pipeforge.client.RunAttempt;
import org.devlive.pipeforge.client.RunCheckpoint;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The commands that keep a run going on a cluster: {@code run pause | resume | savepoint | retry |
 * checkpoints | attempts}.
 */
public final class RunRecoveryCommands {

    private RunRecoveryCommands() {}

    /** Times in UTC, so that every machine shows the same. */
    static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    static String time(@Nullable Instant at) {
        return at == null ? "-" : TIME.format(at);
    }

    static String or(@Nullable Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    /** One row per checkpoint, newest first: what it is, when it was taken, how long it took and its size. */
    static String checkpoints(Messages messages, List<RunCheckpoint> checkpoints) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {
            messages.get("run.checkpoints.column.id"),
            messages.get("run.checkpoints.column.type"),
            messages.get("run.checkpoints.column.status"),
            messages.get("run.checkpoints.column.reason"),
            messages.get("run.checkpoints.column.attempt"),
            messages.get("run.checkpoints.column.taken"),
            messages.get("run.checkpoints.column.duration"),
            messages.get("run.checkpoints.column.size"),
            messages.get("run.checkpoints.column.detail")
        });
        for (RunCheckpoint checkpoint : checkpoints) {
            String detail = checkpoint.error() != null
                    ? checkpoint.error().code() + " " + checkpoint.error().message()
                    : checkpoint.name() != null ? checkpoint.name() : checkpoint.path();
            rows.add(new String[] {
                String.valueOf(checkpoint.id()),
                checkpoint.type(),
                checkpoint.status(),
                checkpoint.reason(),
                String.valueOf(checkpoint.attempt()),
                time(checkpoint.triggeredAt()),
                checkpoint.completedAt() == null
                        ? "-"
                        : RunViews.duration(Duration.between(checkpoint.triggeredAt(), checkpoint.completedAt())
                                .toMillis()),
                checkpoint.sizeBytes() == null ? "-" : ClusterCommand.bytes(checkpoint.sizeBytes()),
                detail
            });
        }
        return RunViews.table(rows, java.util.Set.of(1, 2, 3, 5, 8));
    }

    /** One row per attempt, oldest first: its state, when it ran, its rows, where it started from, its error. */
    static String attempts(Messages messages, List<RunAttempt> attempts, Instant now) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {
            messages.get("run.attempts.column.attempt"),
            messages.get("run.attempts.column.status"),
            messages.get("run.attempts.column.started"),
            messages.get("run.attempts.column.elapsed"),
            messages.get("run.attempts.column.read"),
            messages.get("run.attempts.column.written"),
            messages.get("run.attempts.column.from"),
            messages.get("run.attempts.column.detail")
        });
        for (RunAttempt attempt : attempts) {
            String detail = attempt.retryAt() != null
                    ? messages.get(
                            "run.attempts.retry-in",
                            RunViews.duration(Math.max(
                                    0, Duration.between(now, attempt.retryAt()).toMillis())))
                    : attempt.error() != null
                            ? attempt.error().code() + " " + attempt.error().message()
                            : "-";
            rows.add(new String[] {
                String.valueOf(attempt.attempt()),
                attempt.status(),
                time(attempt.startedAt()),
                attempt.elapsedMs() == null ? "-" : RunViews.duration(attempt.elapsedMs()),
                attempt.rowsRead() == null ? "-" : RunViews.count(attempt.rowsRead()),
                attempt.rowsWritten() == null ? "-" : RunViews.count(attempt.rowsWritten()),
                attempt.restorePath() == null ? messages.get("run.attempts.from-start") : attempt.restorePath(),
                detail
            });
        }
        return RunViews.table(rows, java.util.Set.of(1, 2, 6, 7));
    }

    /** Stops a running run with a savepoint; with {@code --wait} until it is paused. */
    @Command(name = "pause", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Pause extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @Option(names = "--name", paramLabel = "<name>", descriptionKey = "run.savepoint.name")
        @Nullable
        String name;

        @Option(names = "--wait", descriptionKey = "run.pause.wait")
        boolean await;

        @Option(
                names = "--timeout",
                paramLabel = "<duration>",
                defaultValue = "15m",
                converter = RunCommand.DurationConverter.class,
                descriptionKey = "run.pause.timeout")
        Duration timeout = Duration.ofMinutes(15);

        /** How often the run is asked while waiting; hidden, for tests. */
        @Option(names = "--poll", hidden = true, defaultValue = "1s", converter = RunCommand.DurationConverter.class)
        Duration poll = Duration.ofSeconds(1);

        @Override
        protected ExitCode execute(Session session) throws InterruptedException {
            Run run = session.client().pauseRun(id, name);
            if (!await || !run.status().equals("PAUSING")) {
                session.print(run, session.messages().get("run.pause.asked", run.id(), run.status()) + "\n");
                return ExitCode.SUCCESS;
            }
            Instant deadline = Instant.now().plus(timeout);
            while (run.status().equals("PAUSING")) {
                if (!Instant.now().isBefore(deadline)) {
                    session.err()
                            .println(session.messages()
                                    .get("run.pause.timeout-message", id, RunViews.duration(timeout.toMillis())));
                    return ExitCode.TIMEOUT;
                }
                Thread.sleep(poll.toMillis());
                run = session.client().run(id).run();
            }
            if (!run.status().equals("PAUSED")) {
                session.err().println(session.messages().get("run.pause.failed", id, run.status()));
                return ExitCode.ERROR;
            }
            session.print(run, session.messages().get("run.pause.done", run.id()) + "\n");
            return ExitCode.SUCCESS;
        }
    }

    /** Starts a paused run again from its savepoint. */
    @Command(name = "resume", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Resume extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @Override
        protected ExitCode execute(Session session) {
            Run run = session.client().resumeRun(id);
            session.print(run, session.messages().get("run.resume.done", run.id(), run.status()) + "\n");
            return ExitCode.SUCCESS;
        }
    }

    /** Takes a savepoint of a running run. */
    @Command(name = "savepoint", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Savepoint extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @Option(names = "--name", paramLabel = "<name>", descriptionKey = "run.savepoint.name")
        @Nullable
        String name;

        @Override
        protected ExitCode execute(Session session) {
            RunCheckpoint taken = session.client().savepoint(id, name);
            session.print(
                    taken,
                    session.messages()
                                    .get(
                                            "run.savepoint.done",
                                            taken.id(),
                                            id,
                                            taken.path(),
                                            taken.sizeBytes() == null ? "-" : ClusterCommand.bytes(taken.sizeBytes()))
                            + "\n");
            return ExitCode.SUCCESS;
        }
    }

    /** Where a retry starts; neither picks the checkpoint when the run has one. */
    static final class From {
        @Option(names = "--from-checkpoint", required = true, descriptionKey = "run.retry.from-checkpoint")
        boolean checkpoint;

        @Option(names = "--from-scratch", required = true, descriptionKey = "run.retry.from-scratch")
        boolean scratch;
    }

    /** Starts the next attempt of a run that failed, timed out or was cancelled. */
    @Command(name = "retry", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Retry extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @ArgGroup(exclusive = true)
        @Nullable
        From from;

        @Override
        protected ExitCode execute(Session session) {
            String where = from == null ? null : from.checkpoint ? "CHECKPOINT" : "SCRATCH";
            Run run = session.client().retryRun(id, where);
            session.print(run, session.messages().get("run.retry.done", run.id(), run.status()) + "\n");
            return ExitCode.SUCCESS;
        }
    }

    /** The checkpoints and savepoints of a run. */
    @Command(name = "checkpoints", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Checkpoints extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @Override
        protected ExitCode execute(Session session) {
            List<RunCheckpoint> list = session.client().checkpoints(id);
            session.print(
                    list,
                    list.isEmpty()
                            ? session.messages().get("run.checkpoints.none", id) + "\n"
                            : checkpoints(session.messages(), list));
            return ExitCode.SUCCESS;
        }
    }

    /** The attempts of a run. */
    @Command(name = "attempts", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Attempts extends BaseCommand {

        @Parameters(paramLabel = "<run>", descriptionKey = "run.id")
        String id;

        @Override
        protected ExitCode execute(Session session) {
            List<RunAttempt> list = session.client().attempts(id);
            session.print(list, attempts(session.messages(), list, Instant.now()));
            return ExitCode.SUCCESS;
        }
    }
}
