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

/** The cluster of the server: {@code cluster status} and {@code cluster workers}. */
@Command(
        name = "cluster",
        mixinStandardHelpOptions = true,
        subcommands = {ClusterCommand.Status.class, ClusterCommand.Workers.class})
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
                worker.id(),
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
}
