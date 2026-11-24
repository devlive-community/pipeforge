/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.checkpoint;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.zip.CRC32C;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.CheckpointSettings;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointPaths;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.rpc.CheckpointAck;
import org.devlive.pipeforge.engine.rpc.CheckpointFiles;
import org.devlive.pipeforge.engine.rpc.CheckpointTrigger;
import org.devlive.pipeforge.engine.rpc.CheckpointType;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.ProtoMapping;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.StateHandle;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.CheckpointMetadata;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.OperatorState;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.SubtaskState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes the checkpoints of one run (checkpoint design, section 3; protocol specification, section
 * 2.6). It snapshots the enumerators, triggers the source tasks and collects an ack from every
 * task; once all arrived it writes {@code _metadata}, records the checkpoint as completed in the
 * {@link CheckpointStore} and only then tells the tasks, so that sinks commit only what a recorded
 * checkpoint covers. A checkpoint fails when a task declines, it times out, or it cannot be written
 * or recorded; more failures in a row than the job tolerates fail the run.
 *
 * <p>Every method runs on the job master's thread; {@link Context#post} brings the answers of the
 * tasks back to it. Deleting old checkpoints runs on {@link Context#io}.
 */
public final class CheckpointCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(CheckpointCoordinator.class);

    /** Runs an action after a delay on the job master's thread; the result cancels it. */
    public interface Timer {
        Runnable schedule(Runnable action, long delayMs);
    }

    /** The tasks of the run as they are now. */
    public interface Tasks {
        /** The tasks that read the sources: they are triggered, the others follow the barriers. */
        List<ExecutionAttemptId> sources();

        /** Every task: each acknowledges. */
        List<ExecutionAttemptId> all();

        /** Whether every task runs; checkpoints start only then. */
        boolean running();

        TaskExecutorGateway gateway(ExecutionAttemptId task);
    }

    /** Takes the state of the split enumerators, by node; called once per checkpoint. */
    public interface Enumerators {
        Map<String, SerializedValue> snapshot(long checkpointId);
    }

    /**
     * Holds split assignments while a checkpoint runs ({@link SplitAssignmentValve}); the
     * coordinator triggers the sources once {@link #close} completed.
     */
    public interface Valve {
        CompletableFuture<Void> close(long checkpointId);

        void open(ExecutionAttemptId source, long checkpointId);

        void openAll(long checkpointId);

        /** For runs without split assignments to hold. */
        Valve NONE = new Valve() {
            @Override
            public CompletableFuture<Void> close(long checkpointId) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void open(ExecutionAttemptId source, long checkpointId) {}

            @Override
            public void openAll(long checkpointId) {}
        };
    }

    /**
     * Commits the committables of a completed checkpoint: the sinks' committers, then a global
     * committer. Called once the checkpoint is recorded as completed, before the tasks hear of it;
     * committing must be idempotent, since a restore commits them again.
     */
    @FunctionalInterface
    public interface Committer {
        void commit(long checkpointId, List<SerializedValue> committables);

        /** A checkpoint completed and is now the one restarted tasks restore; after the commit. */
        default void completed(long checkpointId) {}

        Committer NONE = (checkpointId, committables) -> {};
    }

    /** A node of the job, as {@code _metadata} describes it. */
    public record Node(String id, String kind, String plugin, int parallelism) {}

    /** What the run is. */
    public record Run(
            String runId,
            String jobName,
            int attempt,
            Map<String, String> variables,
            String engineVersion,
            List<Node> nodes) {}

    /**
     * Where the coordinator runs.
     *
     * @param post runs an action on the job master's thread
     * @param io runs slow storage work that nothing waits for, such as deleting old checkpoints
     * @param clock the current time in milliseconds since the epoch
     * @param failRun fails the run, on the job master's thread
     */
    public record Context(
            CheckpointStorage storage,
            CheckpointStore store,
            Tasks tasks,
            Enumerators enumerators,
            Committer committer,
            Valve valve,
            Timer timer,
            Executor post,
            Executor io,
            LongSupplier clock,
            Consumer<PipeForgeException> failRun) {}

    /** The checkpoint metrics of the run. */
    public record Stats(
            long lastCompletedId, long lastDurationMs, long lastSize, long failures, long lastCompletedAtMs) {

        public static final Stats NONE = new Stats(0, 0, 0, 0, 0);
    }

    /**
     * The states of the last completed checkpoint, by vertex and subtask, for tasks that restart
     * from it.
     */
    public record Latest(
            long checkpointId, Map<String, Map<Integer, List<StateHandle>>> states, @Nullable String directory) {
        public static final Latest NONE = new Latest(0, Map.of());

        /** The states of the run's own checkpoint {@code checkpointId}. */
        public Latest(long checkpointId, Map<String, Map<Integer, List<StateHandle>>> states) {
            this(checkpointId, states, null);
        }

        /** The states of a task; empty when it had none or there is no checkpoint. */
        public List<StateHandle> of(String vertex, int subtask) {
            return states.getOrDefault(vertex, Map.of()).getOrDefault(subtask, List.of());
        }
    }

    /** A checkpoint that completed. */
    public record Completed(long checkpointId, CheckpointType type, String directory, long size, long completedAtMs) {}

    private final class Pending {
        final long id;
        final CheckpointType type;
        final String directory;
        final long triggeredAt;
        final Set<ExecutionAttemptId> waiting;
        final List<CheckpointAck> acks = new ArrayList<>();
        final CompletableFuture<Completed> result = new CompletableFuture<>();
        Map<String, SerializedValue> enumerators = Map.of();
        Runnable cancelTimeout = () -> {};

        Pending(long id, CheckpointType type, String directory, long triggeredAt, Set<ExecutionAttemptId> waiting) {
            this.id = id;
            this.type = type;
            this.directory = directory;
            this.triggeredAt = triggeredAt;
            this.waiting = waiting;
        }
    }

    private final CheckpointSettings settings;
    private final Run run;
    private final Context context;
    private final int inlineThreshold;
    private final Map<Long, Pending> pending = new TreeMap<>();
    /** The completed checkpoints that are kept, the oldest first; savepoints are not among them. */
    private final Deque<Completed> retained = new ArrayDeque<>();

    private Latest latest = Latest.NONE;

    private long lastId;
    private long lastEndMs;
    private int failures;
    private Stats stats = Stats.NONE;
    private boolean waitingForSlot;
    private boolean stopped;
    private Runnable cancelTick = () -> {};

    /**
     * @param restoredId the checkpoint the run restored from, 0 for none: numbers continue after
     *     it and after every number the store already has for the run
     */
    public CheckpointCoordinator(CheckpointSettings settings, Run run, Context context, long restoredId) {
        this(settings, run, context, restoredId, CheckpointFiles.DEFAULT_INLINE_THRESHOLD);
    }

    CheckpointCoordinator(CheckpointSettings settings, Run run, Context context, long restoredId, int inlineThreshold) {
        this.settings = settings;
        this.run = run;
        this.context = context;
        this.inlineThreshold = inlineThreshold;
        this.lastId = Math.max(restoredId, context.store().lastCheckpointId(run.runId()));
    }

    // ------------------------------------------------------------------------------- scheduling

    /** Starts the periodic checkpoints; nothing when the job turned them off. */
    public void start() {
        if (settings.enabled()) {
            cancelTick =
                    context.timer().schedule(this::tick, settings.interval().toMillis());
        }
    }

    private void tick() {
        if (stopped) {
            return;
        }
        if (pending.size() >= settings.maxConcurrent()) {
            // resumed when one of them ends
            waitingForSlot = true;
            return;
        }
        long sinceEnd = context.clock().getAsLong() - lastEndMs;
        long minPause = settings.minPause().toMillis();
        if (lastEndMs > 0 && sinceEnd < minPause) {
            cancelTick = context.timer().schedule(this::tick, minPause - sinceEnd);
            return;
        }
        if (context.tasks().running()) {
            trigger(CheckpointType.CHECKPOINT, "PERIODIC", null);
        }
        cancelTick = context.timer().schedule(this::tick, settings.interval().toMillis());
    }

    // ---------------------------------------------------------------------------------- trigger

    /**
     * Takes a checkpoint now.
     *
     * @param reason why, as recorded: {@code PERIODIC}, {@code SAVEPOINT}, ...
     * @param name the name of a savepoint; its directory is {@code savepoints/<name>-<time>}
     * @return completes when the checkpoint completes, or fails with why it failed
     */
    public CompletableFuture<Completed> trigger(CheckpointType type, String reason, @Nullable String name) {
        if (stopped || !context.tasks().running()) {
            return CompletableFuture.failedFuture(failed(lastId + 1, "the run is not running"));
        }
        long id = ++lastId;
        long now = context.clock().getAsLong();
        String directory = type == CheckpointType.SAVEPOINT
                ? CheckpointPaths.savepoint(run.runId(), name == null ? "savepoint" : name, now)
                : CheckpointPaths.checkpoint(run.runId(), id);
        Pending checkpoint = new Pending(
                id, type, directory, now, new LinkedHashSet<>(context.tasks().all()));
        pending.put(id, checkpoint);
        try {
            context.store()
                    .begin(new CheckpointStore.Checkpoint(
                            run.runId(),
                            id,
                            run.jobName(),
                            run.attempt(),
                            type,
                            CheckpointStore.Status.IN_PROGRESS,
                            reason,
                            name,
                            Instant.ofEpochMilli(now),
                            null,
                            null,
                            directory,
                            null,
                            null,
                            null));
        } catch (PipeForgeException e) {
            // not recorded: nothing to mark as failed in the store
            end(checkpoint, failed(id, "it could not be recorded: " + e.getMessage()), true, false);
            return checkpoint.result;
        }
        try {
            checkpoint.enumerators = context.enumerators().snapshot(id);
        } catch (PipeForgeException e) {
            end(checkpoint, e, true, true);
            return checkpoint.result;
        }
        checkpoint.cancelTimeout =
                context.timer().schedule(() -> timeout(id), settings.timeout().toMillis());
        LOG.info("Run {}: checkpoint {} ({}) triggered", run.runId(), id, reason);
        // split events sent before the enumerator's snapshot reach the readers before the trigger
        context.valve().close(id).whenComplete((ignored, error) -> context.post()
                .execute(() -> {
                    if (pending.get(id) == checkpoint) {
                        triggerSources(checkpoint, type, now);
                    }
                }));
        return checkpoint.result;
    }

    private void triggerSources(Pending checkpoint, CheckpointType type, long now) {
        long id = checkpoint.id;
        for (ExecutionAttemptId source : context.tasks().sources()) {
            context.tasks()
                    .gateway(source)
                    .triggerCheckpoint(new CheckpointTrigger(source, id, type, now, false))
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            context.post().execute(() -> decline(source, id, PipeForgeException.wrap(error)));
                        }
                    });
        }
    }

    // -------------------------------------------------------------------------------- answers

    /** A task took its part. Late or unknown acks, of checkpoints that already ended, are ignored. */
    public void acknowledge(CheckpointAck ack) {
        Pending checkpoint = pending.get(ack.checkpointId());
        if (checkpoint == null || !checkpoint.waiting.remove(ack.task())) {
            return;
        }
        checkpoint.acks.add(ack);
        if (context.tasks().sources().contains(ack.task())) {
            // the reader's snapshot holds what it was sent: assignments held since may go out
            context.valve().open(ack.task(), ack.checkpointId());
        }
        if (checkpoint.waiting.isEmpty()) {
            complete(checkpoint);
        }
    }

    /** A task cannot take part: the checkpoint fails. */
    public void decline(ExecutionAttemptId task, long checkpointId, PipeForgeException reason) {
        Pending checkpoint = pending.get(checkpointId);
        if (checkpoint == null) {
            return;
        }
        PipeForgeException declined = reason.code() == ErrorCode.CHECKPOINT_DECLINED
                ? reason
                : PipeForgeException.of(ErrorCode.CHECKPOINT_DECLINED)
                        .param("checkpoint", checkpointId)
                        .param("task", task.toString())
                        .param("reason", reason.getMessage())
                        .cause(reason)
                        .build();
        end(checkpoint, declined, true, true);
    }

    private void timeout(long id) {
        Pending checkpoint = pending.get(id);
        if (checkpoint == null) {
            return;
        }
        List<String> missing =
                checkpoint.waiting.stream().map(ExecutionAttemptId::toString).toList();
        end(
                checkpoint,
                PipeForgeException.of(ErrorCode.CHECKPOINT_TIMEOUT)
                        .param("checkpoint", id)
                        .param("timeoutMs", settings.timeout().toMillis())
                        .param("missing", String.join(", ", missing))
                        .build(),
                true,
                true);
    }

    // ------------------------------------------------------------------------------- complete

    private void complete(Pending checkpoint) {
        long completedAt = context.clock().getAsLong();
        long size;
        String tasksJson = tasksJson(checkpoint.acks);
        try {
            size = write(checkpoint, completedAt);
        } catch (IOException | RuntimeException e) {
            end(
                    checkpoint,
                    failed(checkpoint.id, "writing it to " + context.storage().location() + ": " + e.getMessage()),
                    true,
                    true);
            return;
        }
        try {
            context.store()
                    .complete(
                            run.runId(),
                            checkpoint.id,
                            run.attempt(),
                            Instant.ofEpochMilli(completedAt),
                            size,
                            tasksJson);
        } catch (PipeForgeException e) {
            end(checkpoint, failed(checkpoint.id, "recording it as completed: " + e.getMessage()), true, true);
            return;
        }
        // recorded as completed: only now may the sinks commit
        pending.remove(checkpoint.id);
        checkpoint.cancelTimeout.run();
        context.valve().openAll(checkpoint.id);
        failures = 0;
        lastEndMs = completedAt;
        stats = new Stats(checkpoint.id, completedAt - checkpoint.triggeredAt, size, 0, completedAt);
        try {
            List<SerializedValue> committables = committables(checkpoint);
            if (!committables.isEmpty()) {
                context.committer().commit(checkpoint.id, committables);
            }
        } catch (IOException | RuntimeException e) {
            // the checkpoint stays completed: a restore from it commits its committables again
            context.failRun()
                    .accept(
                            e instanceof PipeForgeException failure
                                    ? failure
                                    : failed(checkpoint.id, "reading its committables: " + e.getMessage()));
        }
        for (ExecutionAttemptId task : context.tasks().all()) {
            context.tasks()
                    .gateway(task)
                    .notifyCheckpointComplete(task, checkpoint.id)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            LOG.warn(
                                    "Run {}: telling {} that checkpoint {} completed failed",
                                    run.runId(),
                                    task,
                                    checkpoint.id,
                                    error);
                        }
                    });
        }
        Completed completed = new Completed(checkpoint.id, checkpoint.type, checkpoint.directory, size, completedAt);
        LOG.info(
                "Run {}: checkpoint {} completed in {} ms, {} bytes",
                run.runId(),
                checkpoint.id,
                completedAt - checkpoint.triggeredAt,
                size);
        if (checkpoint.type == CheckpointType.CHECKPOINT) {
            Map<String, Map<Integer, List<StateHandle>>> states = new java.util.HashMap<>();
            for (CheckpointAck ack : checkpoint.acks) {
                states.computeIfAbsent(ack.task().vertexId(), v -> new java.util.HashMap<>())
                        .put(ack.task().subtask(), List.copyOf(ack.states()));
            }
            latest = new Latest(checkpoint.id, states);
            context.committer().completed(checkpoint.id);
            retained.addLast(completed);
            while (retained.size() > settings.retained()) {
                discard(retained.removeFirst());
            }
        }
        if (!staging(checkpoint).equals(checkpoint.directory)) {
            deleteFiles(staging(checkpoint));
        }
        checkpoint.result.complete(completed);
        resume();
    }

    // ---------------------------------------------------------------------------------- finish

    /**
     * Every task of a bounded run finished: records a last checkpoint that holds what the sinks
     * prepared and did not commit yet, then commits it (design 06 section 8). The checkpoints in
     * progress are given up and no more are taken. The tasks are not told: they ended.
     *
     * @param sink the node whose committables these are
     * @param committables what each sink task reported when it finished
     * @return the id of the checkpoint
     * @throws PipeForgeException if it could not be written or recorded; nothing was committed then
     */
    public long finish(String sink, Map<ExecutionAttemptId, List<SerializedValue>> committables) {
        cancelTick.run();
        abortAll("the run finished");
        long id = ++lastId;
        long now = context.clock().getAsLong();
        String directory = CheckpointPaths.checkpoint(run.runId(), id);
        Pending checkpoint = new Pending(id, CheckpointType.CHECKPOINT, directory, now, new LinkedHashSet<>());
        committables.forEach((task, values) -> {
            if (!values.isEmpty()) {
                List<byte[]> elements = new ArrayList<>();
                values.forEach(value -> elements.add(value.bytes()));
                byte[] body = CheckpointFiles.writeList(elements);
                checkpoint.acks.add(new CheckpointAck(
                        task,
                        id,
                        List.of(new StateHandle(
                                sink,
                                "committer",
                                values.get(0).version(),
                                new StateHandle.Inline(body),
                                body.length,
                                crc(body))),
                        org.devlive.pipeforge.engine.rpc.CheckpointTaskMetrics.NONE));
            }
        });
        context.store()
                .begin(new CheckpointStore.Checkpoint(
                        run.runId(),
                        id,
                        run.jobName(),
                        run.attempt(),
                        CheckpointType.CHECKPOINT,
                        CheckpointStore.Status.IN_PROGRESS,
                        "FINISH",
                        null,
                        Instant.ofEpochMilli(now),
                        null,
                        null,
                        directory,
                        null,
                        null,
                        null));
        long size;
        try {
            size = write(checkpoint, now);
            context.store()
                    .complete(
                            run.runId(),
                            id,
                            run.attempt(),
                            Instant.ofEpochMilli(now),
                            size,
                            tasksJson(checkpoint.acks));
        } catch (IOException | RuntimeException e) {
            PipeForgeException error = failed(id, "writing or recording the last checkpoint: " + e.getMessage());
            try {
                context.store()
                        .fail(
                                run.runId(),
                                id,
                                Instant.ofEpochMilli(now),
                                error.code().id(),
                                error.getMessage());
            } catch (PipeForgeException ignored) {
                // the record stays in progress; the orphan scan removes its files
            }
            deleteFiles(directory);
            throw error;
        }
        long completedAt = context.clock().getAsLong();
        stats = new Stats(id, completedAt - now, size, 0, completedAt);
        retained.addLast(new Completed(id, CheckpointType.CHECKPOINT, directory, size, completedAt));
        LOG.info(
                "Run {}: last checkpoint {} recorded with what {} sink task(s) prepared",
                run.runId(),
                id,
                checkpoint.acks.size());
        try {
            List<SerializedValue> all = committables(checkpoint);
            if (!all.isEmpty()) {
                context.committer().commit(id, all);
            }
        } catch (IOException e) {
            throw failed(id, "reading its committables: " + e.getMessage());
        }
        return id;
    }

    /** Writes the {@code _metadata} of a checkpoint and marks it complete; returns its size. */
    private long write(Pending checkpoint, long completedAt) throws IOException {
        CheckpointMetadata.Builder metadata = CheckpointMetadata.newBuilder()
                .setJobId(run.runId())
                .setRunId(run.runId())
                .setCheckpointId(checkpoint.id)
                .setType(ProtoMapping.toProto(checkpoint.type))
                .setTriggerTimestamp(checkpoint.triggeredAt)
                .setCompleteTimestamp(completedAt)
                .setEngineVersion(run.engineVersion())
                .setDslVersion("1")
                .putAllVariables(run.variables())
                .setAttempt(run.attempt())
                .setJobName(run.jobName());
        long size = operators(checkpoint, metadata);
        copyStaged(checkpoint);
        byte[] bytes = CheckpointFiles.writeMetadata(metadata.build());
        context.storage().complete(checkpoint.directory, bytes);
        return size + bytes.length;
    }

    /** The committables the sinks prepared for a checkpoint, from its acknowledgements. */
    private List<SerializedValue> committables(Pending checkpoint) throws IOException {
        List<SerializedValue> committables = new ArrayList<>();
        for (CheckpointAck ack : checkpoint.acks) {
            committables.addAll(committables(checkpoint, ack));
        }
        return committables;
    }

    /**
     * The committables one task prepared for a checkpoint, read from where the tasks wrote them
     * (a savepoint's files are copied from there, and removed only after its commit).
     */
    private List<SerializedValue> committables(Pending checkpoint, CheckpointAck ack) throws IOException {
        List<SerializedValue> committables = new ArrayList<>();
        for (StateHandle handle : ack.states()) {
            if (!handle.operatorKind().equals("committer")) {
                continue;
            }
            byte[] body =
                    switch (handle.location()) {
                        case StateHandle.Inline inline -> inline.bytes();
                        case StateHandle.File file -> CheckpointFiles.readState(
                                context.storage().read(staging(checkpoint) + file.path()),
                                checkpoint.id,
                                handle.nodeId(),
                                file.path());
                    };
            for (byte[] element : CheckpointFiles.readList(body, handle.nodeId(), String.valueOf(checkpoint.id))) {
                committables.add(new SerializedValue(handle.serializerVersion(), element));
            }
        }
        return committables;
    }

    /**
     * The directory where the tasks write the files of a checkpoint: they know its number, not the
     * name of a savepoint, so the files of a savepoint are copied from there into it.
     */
    private String staging(Pending checkpoint) {
        return CheckpointPaths.checkpoint(run.runId(), checkpoint.id);
    }

    private void copyStaged(Pending checkpoint) throws IOException {
        String staging = staging(checkpoint);
        if (staging.equals(checkpoint.directory)) {
            return;
        }
        for (CheckpointAck ack : checkpoint.acks) {
            for (StateHandle handle : ack.states()) {
                if (handle.location() instanceof StateHandle.File file) {
                    context.storage()
                            .write(
                                    checkpoint.directory + file.path(),
                                    context.storage().read(staging + file.path()));
                }
            }
        }
    }

    /** Adds the states of the enumerators and the tasks to {@code _metadata}; the size of them all. */
    private long operators(Pending checkpoint, CheckpointMetadata.Builder metadata) throws IOException {
        Map<String, OperatorState.Builder> operators = new LinkedHashMap<>();
        Map<String, Node> nodes = new LinkedHashMap<>();
        run.nodes().forEach(node -> nodes.put(node.id(), node));
        long size = 0;
        for (Map.Entry<String, SerializedValue> entry : checkpoint.enumerators.entrySet()) {
            String node = entry.getKey();
            byte[] state = entry.getValue().bytes();
            SubtaskState.Builder subtask = SubtaskState.newBuilder()
                    .setSubtask(0)
                    .setSerializerVersion(entry.getValue().version())
                    .setSize(state.length)
                    .setCrc32C(crc(state));
            if (CheckpointFiles.inline(state.length, inlineThreshold)) {
                subtask.setInline(ByteString.copyFrom(state));
            } else {
                String file = CheckpointFiles.enumeratorFile(node);
                context.storage()
                        .write(checkpoint.directory + file, CheckpointFiles.writeState(checkpoint.id, node, state));
                subtask.setFile(file);
            }
            operators
                    .computeIfAbsent("enum:" + node, k -> operator(nodes, node, "enumerator", 1))
                    .addSubtasks(subtask);
            size += state.length;
        }
        for (CheckpointAck ack : checkpoint.acks) {
            for (StateHandle handle : ack.states()) {
                SubtaskState.Builder subtask = SubtaskState.newBuilder()
                        .setSubtask(ack.task().subtask())
                        .setSerializerVersion(handle.serializerVersion())
                        .setSize(handle.size())
                        .setCrc32C(handle.crc32c());
                switch (handle.location()) {
                    case StateHandle.Inline inline -> subtask.setInline(ByteString.copyFrom(inline.bytes()));
                    case StateHandle.File file -> subtask.setFile(file.path());
                }
                Node node = nodes.get(handle.nodeId());
                operators
                        .computeIfAbsent(
                                handle.operatorKind() + ":" + handle.nodeId(),
                                k -> operator(
                                        nodes,
                                        handle.nodeId(),
                                        handle.operatorKind(),
                                        node == null ? 0 : node.parallelism()))
                        .addSubtasks(subtask);
                size += handle.size();
            }
        }
        operators.values().forEach(metadata::addOperators);
        return size;
    }

    private static OperatorState.Builder operator(Map<String, Node> nodes, String id, String kind, int parallelism) {
        Node node = nodes.get(id);
        return OperatorState.newBuilder()
                .setNodeId(id)
                .setOperatorKind(kind)
                .setPlugin(node == null ? "" : node.plugin())
                .setMaxParallelism(Math.max(parallelism, 1))
                .setParallelism(parallelism)
                .setStateBackend("heap");
    }

    private static int crc(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    /** Each task's part, as the checkpoint table keeps it. */
    static String tasksJson(List<CheckpointAck> acks) {
        StringBuilder json = new StringBuilder("[");
        for (CheckpointAck ack : acks) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"task\":\"")
                    .append(ack.task().toString().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\"states\":")
                    .append(ack.states().size())
                    .append(",\"syncMs\":")
                    .append(ack.metrics().syncMs())
                    .append(",\"asyncMs\":")
                    .append(ack.metrics().asyncMs())
                    .append(",\"alignmentMs\":")
                    .append(ack.metrics().alignmentMs())
                    .append(",\"stateBytes\":")
                    .append(ack.metrics().stateBytes())
                    .append('}');
        }
        return json.append(']').toString();
    }

    // ---------------------------------------------------------------------------------- failure

    /**
     * Gives a checkpoint up: records it as failed, deletes what was written of it and tells the
     * tasks. A failed checkpoint, not a savepoint, counts toward the failures the job tolerates.
     */
    private void end(Pending checkpoint, PipeForgeException error, boolean counts, boolean recorded) {
        if (pending.remove(checkpoint.id) == null) {
            return;
        }
        checkpoint.cancelTimeout.run();
        context.valve().openAll(checkpoint.id);
        long now = context.clock().getAsLong();
        lastEndMs = now;
        LOG.warn("Run {}: checkpoint {} failed: {}", run.runId(), checkpoint.id, error.getMessage());
        if (recorded) {
            try {
                context.store()
                        .fail(
                                run.runId(),
                                checkpoint.id,
                                Instant.ofEpochMilli(now),
                                error.code().id(),
                                error.getMessage());
            } catch (PipeForgeException e) {
                LOG.warn("Run {}: recording that checkpoint {} failed failed", run.runId(), checkpoint.id, e);
            }
        }
        deleteFiles(checkpoint.directory);
        if (!staging(checkpoint).equals(checkpoint.directory)) {
            deleteFiles(staging(checkpoint));
        }
        for (ExecutionAttemptId task : context.tasks().all()) {
            context.tasks().gateway(task).abortCheckpoint(task, checkpoint.id, error.getMessage());
        }
        checkpoint.result.completeExceptionally(error);
        if (counts && checkpoint.type == CheckpointType.CHECKPOINT) {
            failures++;
            stats = new Stats(
                    stats.lastCompletedId(),
                    stats.lastDurationMs(),
                    stats.lastSize(),
                    failures,
                    stats.lastCompletedAtMs());
            if (failures > settings.tolerableFailures() && !stopped) {
                context.failRun()
                        .accept(PipeForgeException.of(ErrorCode.TOO_MANY_CHECKPOINT_FAILURES)
                                .param("failures", failures)
                                .param("limit", settings.tolerableFailures())
                                .param("last", error.getMessage())
                                .cause(error)
                                .build());
                return;
            }
        }
        resume();
    }

    private void resume() {
        if (waitingForSlot && !stopped) {
            waitingForSlot = false;
            tick();
        }
    }

    private PipeForgeException failed(long id, String reason) {
        return PipeForgeException.of(ErrorCode.CHECKPOINT_FAILED)
                .param("checkpoint", id)
                .param("reason", reason)
                .build();
    }

    // ------------------------------------------------------------------------------- cleanup

    /** Deletes a completed checkpoint that is no longer kept: its record first, then its files. */
    private void discard(Completed completed) {
        try {
            context.store().delete(run.runId(), completed.checkpointId());
        } catch (PipeForgeException e) {
            LOG.warn(
                    "Run {}: forgetting checkpoint {} failed; its files stay",
                    run.runId(),
                    completed.checkpointId(),
                    e);
            return;
        }
        deleteFiles(completed.directory());
    }

    private void deleteFiles(String directory) {
        context.io().execute(() -> {
            try {
                context.storage().delete(directory);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Run {}: deleting {} failed; the orphan scan removes it later", run.runId(), directory, e);
            }
        });
    }

    /**
     * The run ended: checkpoints in progress are given up, and the kept ones deleted when the run
     * succeeded, or was cancelled with {@code DELETE_ON_CANCELLATION}. A failed run keeps them, to
     * start again from the last.
     */
    public void stop(JobStatus end) {
        if (stopped) {
            return;
        }
        stopped = true;
        cancelTick.run();
        for (Pending checkpoint : List.copyOf(pending.values())) {
            end(checkpoint, failed(checkpoint.id, "the run ended (" + end + ")"), false, true);
        }
        boolean delete = end == JobStatus.SUCCESS
                || (end == JobStatus.CANCELLED
                        && settings.externalized() == CheckpointSettings.Externalized.DELETE_ON_CANCELLATION);
        if (delete) {
            while (!retained.isEmpty()) {
                discard(retained.removeFirst());
            }
        }
    }

    // ------------------------------------------------------------------------------- views

    public Stats stats() {
        return stats;
    }

    /** The completed checkpoints that are kept, the oldest first. */
    public List<Completed> retained() {
        return List.copyOf(retained);
    }

    /** The states of the last completed checkpoint, which restarted tasks restore. */
    public Latest latest() {
        return latest;
    }

    /**
     * The run started from a checkpoint or savepoint: until one of its own completes, restarted
     * tasks restore that.
     */
    public void restoredFrom(Latest point) {
        latest = point;
    }

    /**
     * Gives up the checkpoints in progress without counting them as failures, as when tasks
     * restart: those checkpoints can no longer complete.
     */
    public void abortAll(String reason) {
        for (Pending checkpoint : List.copyOf(pending.values())) {
            end(checkpoint, failed(checkpoint.id, reason), false, true);
        }
    }

    /** The checkpoints in progress. */
    public Set<Long> inProgress() {
        return Set.copyOf(pending.keySet());
    }
}
