/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker.log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.LogChunk;
import org.devlive.pipeforge.engine.rpc.LogQuery;
import org.slf4j.MDC;

/**
 * The task logs of a worker: one file per task attempt, {@code
 * <dir>/<job>/<run>/<vertex>-<subtask>-<attempt>.log}.
 *
 * <p>A task {@linkplain #open opens} its log on its own thread; the log is tagged in the MDC with the
 * job, the run and the task, and the {@link TaskLogAppender} of the logging configuration writes every
 * event of a tagged thread into the task's file. A file that would grow past {@link
 * Settings#maxFileBytes()} is rolled: it is renamed with the offset it starts at, so that offsets stay
 * the same for readers, and only the newest {@link Settings#maxFiles()} files are kept. Logs stay
 * readable after the task ended, until {@link #cleanup} removes runs older than {@link
 * Settings#retention()}.
 */
public final class LogService implements AutoCloseable {

    /** The MDC keys a task's threads carry. */
    public static final String MDC_JOB = "pipeforge.job";

    public static final String MDC_RUN = "pipeforge.run";
    public static final String MDC_TASK = "pipeforge.task";

    /** Routes events to the open log; not meant for log patterns. */
    static final String MDC_LOG = "pipeforge.taskLog";

    private static final Map<String, TaskLog> ROUTES = new ConcurrentHashMap<>();
    private static final AtomicInteger INSTANCES = new AtomicInteger();
    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final long FOLLOW_INTERVAL_MS = 200;

    /**
     * Where and how much the service keeps.
     *
     * @param dir where the logs are written
     * @param maxFileBytes the size at which a file is rolled
     * @param maxFiles how many files of one task are kept, the current one included
     * @param retention how long the logs of a run are kept after their last write
     */
    public record Settings(Path dir, long maxFileBytes, int maxFiles, Duration retention) {

        public Settings {
            Objects.requireNonNull(dir, "dir");
            Objects.requireNonNull(retention, "retention");
            if (maxFileBytes < 1024) {
                throw new IllegalArgumentException("a task log file must hold at least 1KB, not " + maxFileBytes);
            }
            if (maxFiles < 1) {
                throw new IllegalArgumentException("at least one task log file must be kept, not " + maxFiles);
            }
        }

        /** 64MB per file, 5 files, 7 days. */
        public static Settings defaults(Path dir) {
            return new Settings(dir, 64L * 1024 * 1024, 5, Duration.ofDays(7));
        }
    }

    private final String worker;
    private final Settings settings;
    private final String prefix = "log-" + INSTANCES.incrementAndGet() + ":";
    private final Map<ExecutionAttemptId, TaskLog> logs = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> following = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService followers;

    /** @param worker how errors name the worker */
    public LogService(String worker, Settings settings) {
        this.worker = worker;
        this.settings = settings;
        this.followers = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "task-log-" + worker);
            thread.setDaemon(true);
            return thread;
        });
    }

    public Settings settings() {
        return settings;
    }

    /** The file a task writes its log to. */
    public Path path(String jobId, ExecutionAttemptId task) {
        return settings.dir().resolve(safe(jobId)).resolve(safe(task.runId())).resolve(fileName(task));
    }

    private static String fileName(ExecutionAttemptId task) {
        return safe(task.vertexId()) + "-" + task.subtask() + "-" + task.attempt() + ".log";
    }

    static String safe(String name) {
        String safe = SAFE.matcher(name).replaceAll("_");
        return safe.isEmpty() || safe.equals(".") || safe.equals("..") ? "_" + safe : safe;
    }

    /**
     * Opens the log of a task and tags the calling thread with it; closing the scope closes the log
     * and removes the tags.
     *
     * @throws UncheckedIOException if the file cannot be created
     */
    public Scope open(String jobId, ExecutionAttemptId task) {
        Path path = path(jobId, task);
        TaskLog log;
        try {
            log = new TaskLog(path, settings.maxFileBytes(), settings.maxFiles());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String key = prefix + task;
        logs.put(task, log);
        ROUTES.put(key, log);
        Scope scope =
                new Scope(Map.of(MDC_JOB, jobId, MDC_RUN, task.runId(), MDC_TASK, task.toString(), MDC_LOG, key)) {
                    @Override
                    public void close() {
                        ROUTES.remove(key, log);
                        log.close();
                        super.close();
                    }
                };
        scope.enter();
        return scope;
    }

    /** Writes one formatted event to the log the key routes to; events of closed logs are dropped. */
    static void append(String key, byte[] event) {
        TaskLog log = ROUTES.get(key);
        if (log != null) {
            log.append(event);
        }
    }

    /**
     * Reads the log of a task: one chunk, or with {@link LogQuery#follow()} chunks until the task's
     * log is closed. Cancelling the future stops following.
     */
    @SuppressWarnings("FutureReturnValueIgnored") // the callback only forgets the finished reader
    public CompletableFuture<Void> read(ExecutionAttemptId task, LogQuery query, Consumer<LogChunk> chunks) {
        TaskLog open = logs.get(task);
        Path path = open != null ? open.path() : find(task);
        if (path == null || !Files.exists(path)) {
            return CompletableFuture.failedFuture(PipeForgeException.of(ErrorCode.TASK_LOG_NOT_FOUND)
                    .param("task", task.toString())
                    .param("worker", worker)
                    .build());
        }
        if (!query.follow()) {
            try {
                chunks.accept(LogFile.read(path, query.offset(), query, open == null || open.closed()));
                return CompletableFuture.completedFuture(null);
            } catch (IOException | RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        following.add(result);
        result.whenComplete((ignored, error) -> following.remove(result));
        new Follower(path, open, query, chunks, result).schedule(0);
        return result;
    }

    private Path find(ExecutionAttemptId task) {
        if (!Files.isDirectory(settings.dir())) {
            return null;
        }
        try (DirectoryStream<Path> jobs = Files.newDirectoryStream(settings.dir(), Files::isDirectory)) {
            for (Path job : jobs) {
                Path candidate = job.resolve(safe(task.runId())).resolve(fileName(task));
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return null;
    }

    /**
     * Removes the logs of runs that nothing wrote to for longer than the retention, and the job
     * directories that are left empty. Runs with an open log are kept.
     *
     * @return how many runs were removed
     */
    public int cleanup(Instant now) {
        Path dir = settings.dir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        Instant before = now.minus(settings.retention());
        List<Path> open = logs.values().stream()
                .filter(log -> !log.closed())
                .map(log -> LogFile.directory(log.path()))
                .toList();
        int removed = 0;
        try (DirectoryStream<Path> jobs = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path job : jobs) {
                try (DirectoryStream<Path> runs = Files.newDirectoryStream(job, Files::isDirectory)) {
                    for (Path run : runs) {
                        if (!open.contains(run) && lastWrite(run).isBefore(before)) {
                            delete(run);
                            removed++;
                        }
                    }
                }
                try (Stream<Path> left = Files.list(job)) {
                    if (left.findAny().isEmpty()) {
                        Files.deleteIfExists(job);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        logs.values().removeIf(log -> log.closed() && !Files.exists(log.path()));
        return removed;
    }

    private static Instant lastWrite(Path run) throws IOException {
        try (Stream<Path> files = Files.list(run)) {
            return files.map(file -> {
                        try {
                            return Files.getLastModifiedTime(file);
                        } catch (IOException e) {
                            return FileTime.fromMillis(0);
                        }
                    })
                    .max(Comparator.naturalOrder())
                    .orElse(Files.getLastModifiedTime(run))
                    .toInstant();
        }
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(files.toList());
            all.sort(Comparator.reverseOrder());
            for (Path file : all) {
                Files.deleteIfExists(file);
            }
        }
    }

    /** Stops following logs; the logs of running tasks stay open until their tasks close them. */
    @Override
    public void close() {
        following.forEach(f -> f.cancel(false));
        followers.shutdownNow();
    }

    /** The MDC tags of a task's thread; other threads of the task {@linkplain #enter() enter} it too. */
    public static class Scope implements AutoCloseable {
        private final Map<String, String> context;

        Scope(Map<String, String> context) {
            this.context = Map.copyOf(context);
        }

        /** The tags, to hand to other threads of the task. */
        public Map<String, String> context() {
            return context;
        }

        /** Tags the calling thread. */
        public void enter() {
            context.forEach(MDC::put);
        }

        /** Removes the tags from the calling thread. */
        @Override
        public void close() {
            context.keySet().forEach(MDC::remove);
        }
    }

    /** Sends what a running task writes, a chunk at a time. */
    private final class Follower implements Runnable {
        private final Path path;
        private final TaskLog open;
        private final LogQuery query;
        private final Consumer<LogChunk> chunks;
        private final CompletableFuture<Void> result;
        private long offset;

        Follower(Path path, TaskLog open, LogQuery query, Consumer<LogChunk> chunks, CompletableFuture<Void> result) {
            this.path = path;
            this.open = open;
            this.query = query;
            this.chunks = chunks;
            this.result = result;
            this.offset = query.offset();
        }

        @SuppressWarnings("FutureReturnValueIgnored") // the next round is reached through the result
        void schedule(long delayMs) {
            try {
                followers.schedule(this, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                result.cancel(false);
            }
        }

        @Override
        public void run() {
            if (result.isDone()) {
                return;
            }
            try {
                // closed before reading: everything the task wrote is in the file then
                boolean complete = open == null || open.closed();
                LogChunk chunk = LogFile.read(path, offset, query, complete);
                boolean moved = chunk.offset() != offset;
                offset = chunk.offset();
                if (!chunk.text().isEmpty() || chunk.eof()) {
                    chunks.accept(chunk);
                }
                if (chunk.eof()) {
                    result.complete(null);
                } else {
                    schedule(moved ? 0 : FOLLOW_INTERVAL_MS);
                }
            } catch (IOException | RuntimeException e) {
                result.completeExceptionally(e);
            }
        }
    }
}
