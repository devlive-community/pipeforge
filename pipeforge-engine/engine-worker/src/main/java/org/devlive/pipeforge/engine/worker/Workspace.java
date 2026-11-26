/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local disk the runs of a worker use: a work directory per run, with one per task and node in
 * it, which readers and writers get from their context, and the task logs of the run. What a run
 * holds on this worker, both together, is limited by a quota; checked every few seconds, a run over
 * it has its tasks on this worker fail with {@code PF-RES-0004}, and the other runs go on. A task's
 * directory is deleted when the task ends, the run's when its last task here ended.
 *
 * <pre>
 * &lt;root&gt;/&lt;run&gt;/&lt;vertex&gt;-&lt;subtask&gt;-&lt;attempt&gt;/&lt;node&gt;/
 * </pre>
 */
public final class Workspace {

    private static final Logger LOG = LoggerFactory.getLogger(Workspace.class);

    /**
     * Where the work directories are and how much a run may hold.
     *
     * @param root the directory of the work directories; {@code null} for a temporary one, created
     *     when a task first asks
     * @param quotaBytes what one run may hold on the worker, its task logs included; {@link
     *     Long#MAX_VALUE} for no limit
     */
    public record Settings(@Nullable Path root, long quotaBytes) {

        /** A temporary directory and no quota: for local runs and tests. */
        public static final Settings DEFAULTS = new Settings(null, Long.MAX_VALUE);

        public Settings {
            if (quotaBytes <= 0) {
                throw new IllegalArgumentException("the disk quota of a job must be positive");
            }
        }
    }

    private final String worker;
    private final Settings settings;
    private final Supplier<Collection<Task>> tasks;
    private final Function<Task, @Nullable Path> logs;
    private final BiConsumer<Task, PipeForgeException> fail;
    private volatile @Nullable Path root;
    /** Tasks failed for the quota that have not ended yet; they are not failed again. */
    private final Set<ExecutionAttemptId> failed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * @param tasks the tasks of the worker
     * @param logs the directory that holds the log of a task, or {@code null} when it has none
     * @param fail fails a task with an error
     */
    Workspace(
            String worker,
            Settings settings,
            Supplier<Collection<Task>> tasks,
            Function<Task, @Nullable Path> logs,
            BiConsumer<Task, PipeForgeException> fail) {
        this.worker = worker;
        this.settings = settings;
        this.tasks = tasks;
        this.logs = logs;
        this.fail = fail;
        this.root = settings.root();
        Path base = root;
        if (base != null && Files.isDirectory(base)) {
            // left by a worker that stopped while runs used them; no task here uses them now
            try (var stale = Files.list(base)) {
                stale.forEach(Workspace::delete);
            } catch (IOException e) {
                LOG.warn("Cleaning the work directories under {} failed", base, e);
            }
        }
    }

    private static final java.util.regex.Pattern UNSAFE = java.util.regex.Pattern.compile("[^A-Za-z0-9._-]");

    /** A name usable as a file name on every platform. */
    static String safe(String name) {
        String safe = UNSAFE.matcher(name).replaceAll("_");
        return safe.isEmpty() || safe.equals(".") || safe.equals("..") ? "_" + safe : safe;
    }

    /** The work directory of node {@code node} in task {@code task}, created when it does not exist. */
    Path directory(ExecutionAttemptId task, String node) {
        Path directory = taskDirectory(task).resolve(safe(node));
        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the work directory " + directory, e);
        }
    }

    private Path taskDirectory(ExecutionAttemptId task) {
        return runDirectory(task.runId()).resolve(safe(task.vertexId()) + "-" + task.subtask() + "-" + task.attempt());
    }

    private synchronized Path runDirectory(String run) {
        Path base = root;
        if (base == null) {
            try {
                base = Files.createTempDirectory("pipeforge-work-");
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create a temporary work directory", e);
            }
            root = base;
        }
        return base.resolve(safe(run));
    }

    /** Deletes the directory of a task that ended. */
    void taskEnded(ExecutionAttemptId task) {
        failed.remove(task);
        Path base = root;
        if (base != null) {
            delete(taskDirectory(task));
        }
    }

    /** Deletes the directory of a run whose last task on this worker ended. */
    void runEnded(String run) {
        Path base = root;
        if (base != null) {
            delete(base.resolve(safe(run)));
        }
    }

    /** What each run with running tasks holds on this worker now, in bytes. */
    Map<String, Long> usage() {
        Map<String, List<Task>> runs = running();
        Map<String, Long> usage = new LinkedHashMap<>();
        runs.forEach((run, list) -> usage.put(run, used(run, list)));
        return usage;
    }

    /** Fails the tasks of the runs over the quota; called by one thread at a time. */
    synchronized void check() {
        if (settings.quotaBytes() == Long.MAX_VALUE) {
            return;
        }
        for (Map.Entry<String, List<Task>> run : running().entrySet()) {
            long used = used(run.getKey(), run.getValue());
            if (used <= settings.quotaBytes() || run.getValue().stream().allMatch(t -> failed.contains(t.id()))) {
                continue;
            }
            LOG.error(
                    "Run {} holds {} bytes on the disk of worker {}, over its quota of {} bytes; failing its {} task(s)"
                            + " here",
                    run.getKey(),
                    used,
                    worker,
                    settings.quotaBytes(),
                    run.getValue().size());
            for (Task task : run.getValue()) {
                ExecutionAttemptId id = task.id();
                if (!failed.add(id)) {
                    continue;
                }
                fail.accept(
                        task,
                        PipeForgeException.of(ErrorCode.DISK_QUOTA_EXCEEDED)
                                .param("run", run.getKey())
                                .param("worker", worker)
                                .param("bytes", used)
                                .param("quota", settings.quotaBytes())
                                .scope(ErrorScope.node(id.vertexId())
                                        .withRun(id.runId())
                                        .withSubtask(id.subtask()))
                                .build());
            }
        }
    }

    private Map<String, List<Task>> running() {
        Map<String, List<Task>> runs = new LinkedHashMap<>();
        for (Task task : tasks.get()) {
            if (task.state() == TaskState.RUNNING) {
                runs.computeIfAbsent(task.id().runId(), r -> new ArrayList<>()).add(task);
            }
        }
        return runs;
    }

    /** The work directory of the run and the log directories of its tasks, each counted once. */
    private long used(String run, List<Task> tasks) {
        Set<Path> directories = new java.util.LinkedHashSet<>();
        Path base = root;
        if (base != null) {
            directories.add(base.resolve(safe(run)));
        }
        for (Task task : tasks) {
            Path log = logs.apply(task);
            if (log != null) {
                directories.add(log);
            }
        }
        long bytes = 0;
        for (Path directory : directories) {
            bytes += size(directory);
        }
        return bytes;
    }

    /** The size of the files under {@code directory}; files that vanish while it counts are skipped. */
    static long size(Path directory) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        long[] bytes = {0};
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    bytes[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.debug("Counting the files under {} failed", directory, e);
        }
        return bytes[0];
    }

    private static void delete(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                    if (e instanceof NoSuchFileException) {
                        return FileVisitResult.CONTINUE;
                    }
                    throw e;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException e) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Deleting the work directory {} failed", directory, e);
        }
    }
}
