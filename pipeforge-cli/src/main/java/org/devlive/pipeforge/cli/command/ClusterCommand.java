/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Messages;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.client.ClusterView;
import org.devlive.pipeforge.client.Slot;
import org.devlive.pipeforge.client.Worker;
import picocli.CommandLine.Command;

/**
 * The cluster of the server: {@code cluster status} and {@code workers}, {@code drain} and {@code
 * uncordon} of a worker, and {@code failover} of the leading master.
 */
@Command(
        name = "cluster",
        mixinStandardHelpOptions = true,
        subcommands = {
            ClusterCommand.Status.class,
            ClusterCommand.Workers.class,
            ClusterCommand.Drain.class,
            ClusterCommand.Uncordon.class,
            ClusterCommand.Failover.class
        })
public final class ClusterCommand extends BaseCommand {

    /** After this long without a heartbeat a worker is late; the default interval is 5 s. */
    static final long LATE_MS = 15_000;

    /** Without a subcommand: the usage, exit code 2. */
    @Override
    protected ExitCode execute(Session session) {
        spec.commandLine().usage(session.err());
        return ExitCode.USAGE;
    }

    static int used(Worker worker) {
        return (int) worker.slots().stream().filter(Slot::allocated).count();
    }

    static String bytes(long value) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double size = value;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return unit == 0
                ? value + " B"
                : String.format(Locale.ROOT, size >= 10 ? "%.0f %s" : "%.1f %s", size, units[unit]);
    }

    /** The master, the slots in use and the workers, late ones counted. */
    static String status(Messages messages, ClusterView view, Instant now) {
        long late = view.workers().stream()
                .filter(w -> w.sinceHeartbeatMs() > LATE_MS)
                .count();
        String up = view.master().startedAt() == null
                ? "-"
                : RunViews.duration(
                        Duration.between(view.master().startedAt(), now).toMillis());
        return messages.get(
                        "cluster.master",
                        view.master().address(),
                        view.master().version(),
                        view.master().protocol(),
                        up)
                + "\n"
                + messages.get(
                        "cluster.slots",
                        view.slotsUsed(),
                        view.slots(),
                        String.format(Locale.ROOT, "%.0f%%", view.usage() * 100))
                + "\n"
                + messages.get("cluster.workers", view.workers().size())
                + (late > 0 ? messages.get("cluster.late", late) : "")
                + "\n";
    }

    /** One row per worker: its slots and tasks, resources, heartbeat and labels. */
    static String workers(Messages messages, List<Worker> workers) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {
            messages.get("cluster.column.worker"),
            messages.get("cluster.column.address"),
            messages.get("cluster.column.slots"),
            messages.get("cluster.column.tasks"),
            messages.get("cluster.column.cores"),
            messages.get("cluster.column.heap"),
            messages.get("cluster.column.heartbeat"),
            messages.get("cluster.column.labels")
        });
        for (Worker worker : workers.stream()
                .sorted(java.util.Comparator.comparing(Worker::id))
                .toList()) {
            Map<String, String> labels = new TreeMap<>(worker.labels());
            rows.add(new String[] {
                worker.id() + (worker.draining() ? " " + messages.get("cluster.draining-mark") : ""),
                worker.rpcAddress(),
                used(worker) + "/" + worker.slots().size(),
                String.valueOf(worker.tasksRunning()),
                String.valueOf(worker.cpuCores()),
                bytes(worker.taskHeapBytes()),
                RunViews.duration(worker.sinceHeartbeatMs())
                        + (worker.sinceHeartbeatMs() > LATE_MS ? " " + messages.get("cluster.late-mark") : ""),
                labels.isEmpty()
                        ? "-"
                        : String.join(
                                ",",
                                labels.entrySet().stream()
                                        .map(e -> e.getKey() + "=" + e.getValue())
                                        .toList())
            });
        }
        // identity and text left, counts and sizes right
        return RunViews.table(rows, java.util.Set.of(0, 1, 6, 7));
    }

    /** The master, the slots in use and the number of workers. */
    @Command(name = "status", mixinStandardHelpOptions = true)
    public static final class Status extends BaseCommand {
        @Override
        protected ExitCode execute(Session session) {
            ClusterView view = session.client().cluster();
            session.print(view, status(session.messages(), view, Instant.now()));
            return ExitCode.SUCCESS;
        }
    }

    /** The workers with their slots, tasks, resources, heartbeat and labels. */
    @Command(name = "workers", mixinStandardHelpOptions = true)
    public static final class Workers extends BaseCommand {
        @Override
        protected ExitCode execute(Session session) {
            ClusterView view = session.client().cluster();
            session.print(view.workers(), workers(session.messages(), view.workers()));
            return ExitCode.SUCCESS;
        }
    }

    /** Gives a worker no new tasks, and with {@code --wait} waits until its tasks ended. */
    @Command(name = "drain", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Drain extends BaseCommand {

        @picocli.CommandLine.Parameters(paramLabel = "<worker>", descriptionKey = "cluster.worker-id")
        String worker;

        @picocli.CommandLine.Option(names = "--wait", descriptionKey = "cluster.drain.wait")
        boolean await;

        @picocli.CommandLine.Option(
                names = "--timeout",
                paramLabel = "<duration>",
                defaultValue = "30m",
                converter = RunCommand.DurationConverter.class,
                descriptionKey = "cluster.drain.timeout")
        Duration timeout = Duration.ofMinutes(30);

        /** How often the tasks of the worker are counted while waiting; hidden, for tests. */
        @picocli.CommandLine.Option(
                names = "--poll",
                hidden = true,
                defaultValue = "2s",
                converter = RunCommand.DurationConverter.class)
        Duration poll = Duration.ofSeconds(2);

        @Override
        protected ExitCode execute(Session session) throws InterruptedException {
            session.client().drainWorker(worker);
            if (!await) {
                session.print(
                        Map.of("worker", worker, "draining", true),
                        session.messages().get("cluster.drain.done", worker) + "\n");
                return ExitCode.SUCCESS;
            }
            Instant deadline = Instant.now().plus(timeout);
            while (true) {
                Worker found = session.client().cluster().workers().stream()
                        .filter(w -> w.id().equals(worker))
                        .findFirst()
                        .orElse(null);
                int running = found == null ? 0 : found.tasksRunning();
                if (running == 0) {
                    session.print(
                            Map.of("worker", worker, "draining", true, "tasksRunning", 0),
                            session.messages().get("cluster.drain.empty", worker) + "\n");
                    return ExitCode.SUCCESS;
                }
                if (!Instant.now().isBefore(deadline)) {
                    session.err()
                            .println(session.messages()
                                    .get(
                                            "cluster.drain.timeout-left",
                                            worker,
                                            running,
                                            RunViews.duration(timeout.toMillis())));
                    return ExitCode.TIMEOUT;
                }
                session.note(session.messages().get("cluster.drain.waiting", worker, running));
                Thread.sleep(poll.toMillis());
            }
        }
    }

    /** Gives a drained worker new tasks again. */
    @Command(name = "uncordon", mixinStandardHelpOptions = true)
    public static final class Uncordon extends BaseCommand {

        @picocli.CommandLine.Parameters(paramLabel = "<worker>", descriptionKey = "cluster.worker-id")
        String worker;

        @Override
        protected ExitCode execute(Session session) {
            session.client().uncordonWorker(worker);
            session.print(
                    Map.of("worker", worker, "draining", false),
                    session.messages().get("cluster.uncordon.done", worker) + "\n");
            return ExitCode.SUCCESS;
        }
    }

    /** Asks the leading master to give its lease up so that a standby takes over. */
    @Command(name = "failover", mixinStandardHelpOptions = true)
    public static final class Failover extends BaseCommand {

        @picocli.CommandLine.Option(
                names = {"-y", "--yes"},
                descriptionKey = "cluster.failover.yes")
        boolean yes;

        // on JDK 21 the console is null without a terminal; interactive() is checked first anyway
        @SuppressWarnings("SystemConsoleNull")
        @Override
        protected ExitCode execute(Session session) {
            if (!yes) {
                java.io.Console console = System.console();
                if (!session.environment().interactive() || console == null) {
                    throw new picocli.CommandLine.ParameterException(
                            spec.commandLine(), session.messages().get("cluster.failover.confirm-flag"));
                }
                String answer = console.readLine("%s", session.messages().get("cluster.failover.confirm"));
                if (answer == null || !answer.strip().toLowerCase(Locale.ROOT).startsWith("y")) {
                    session.note(session.messages().get("cluster.failover.kept"));
                    return ExitCode.SUCCESS;
                }
            }
            org.devlive.pipeforge.client.Failover done = session.client().failover();
            session.print(done, session.messages().get("cluster.failover.done", done.master()) + "\n");
            return ExitCode.SUCCESS;
        }
    }
}
