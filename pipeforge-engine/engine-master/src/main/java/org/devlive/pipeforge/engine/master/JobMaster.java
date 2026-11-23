/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.factory.SinkFactory;
import org.devlive.pipeforge.api.factory.SourceFactory;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.sink.GlobalCommitter;
import org.devlive.pipeforge.api.sink.Sink;
import org.devlive.pipeforge.api.sink.SinkCommitter;
import org.devlive.pipeforge.api.source.EnumeratorContext;
import org.devlive.pipeforge.api.source.Source;
import org.devlive.pipeforge.api.source.SourceSplit;
import org.devlive.pipeforge.api.source.SplitEnumerator;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorScope;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.expression.EngineExpressions;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobId;
import org.devlive.pipeforge.engine.api.JobMetrics;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.master.graph.ExecutionGraph;
import org.devlive.pipeforge.engine.master.graph.JobGraph;
import org.devlive.pipeforge.engine.rpc.ChainCodec;
import org.devlive.pipeforge.engine.rpc.ChainDescriptor;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.ReaderMessage;
import org.devlive.pipeforge.engine.rpc.RunContext;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.devlive.pipeforge.engine.rpc.TaskDeployment;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.TaskMetricsSnapshot;
import org.devlive.pipeforge.engine.rpc.TaskSettings;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.rpc.TaskStateUpdate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The job master of one run: gets the slots of the job from its {@link Placement}, runs the split
 * enumerator, deploys the tasks of every vertex into the slots with the connections between them,
 * follows their states and decides how the run ends.
 * Everything it does runs on its own thread, one event at a time, so the enumerator and the run
 * state are never touched concurrently.
 *
 * <ul>
 *   <li>Readers pull splits: a split request from a task reaches {@code handleSplitRequest}, and
 *       the splits the enumerator assigns travel to the task serialized, as they would over the
 *       network.
 *   <li>When every task has finished, the committables of the sink are committed (by its
 *       committer, then its global committer) and the job ends with {@code SUCCESS}.
 *   <li>The first failure cancels the other tasks; the job ends with {@code FAILED} and that error.
 *       There is no restart yet.
 *   <li>A worker that is lost fails the run with {@code PF-CLUS-0001}, scoped to the first of its
 *       tasks that was still running.
 *   <li>The slots go back to the placement when the run ends.
 * </ul>
 */
public final class JobMaster {

    private static final Logger LOG = LoggerFactory.getLogger(JobMaster.class);
    private static final int COMMIT_ATTEMPTS = 3;

    private final JobId id;
    private final JobPlan plan;
    private final JobGraph graph;
    private final ExecutionGraph execution;
    private final JobGraph.Vertex sourceVertex;
    private final JobGraph.Vertex sinkVertex;
    private final JobContext job;
    /** What the tasks of every vertex receive: their chain written and read in the task description format. */
    private final Map<String, ChainDescriptor> deployed = new LinkedHashMap<>();

    private final RunContext run;

    /** The number of readers: the parallelism of the source. */
    private final int parallelism;

    private final TaskSettings settings;
    private final Placement placement;
    /** Written once on the job master thread; read by log readers on other threads. */
    private volatile List<Placement.Slot> slots = List.of();

    private final ScheduledExecutorService thread;
    private final MetricsAggregator aggregator;
    private final Handle handle = new Handle();
    private final long started = System.nanoTime();
    private final Instant runStartTime = Instant.now();
    private final ExpressionCompiler expressions;
    private final Map<ExecutionGraph.Task, TaskState> states = new LinkedHashMap<>();
    private final Set<Integer> readers = new LinkedHashSet<>();
    /** How often the quotas of the rate limit are split again. */
    static final Duration QUOTA_PERIOD = Duration.ofSeconds(1);

    private org.devlive.pipeforge.core.plan.RateLimit limit;
    private final QuotaAllocator.Usage usage = new QuotaAllocator.Usage();
    /** The quota each reader has now: rows and bytes per second. */
    private final Map<Integer, double[]> quotas = new HashMap<>();
    /**
     * Messages of readers that are not registered yet: state updates and reader messages travel as
     * separate calls, so a split request can arrive before the RUNNING it follows.
     */
    private final Map<Integer, List<ReaderMessage>> early = new LinkedHashMap<>();

    private final List<SerializedValue> committables = new ArrayList<>();
    private final Runnable onEnded;
    private volatile JobStatus status = JobStatus.CREATED;
    private volatile java.util.function.Consumer<JobStatus> statusListener = s -> {};
    /** The tasks as other threads see them, replaced on every change. */
    private volatile List<TaskView> taskView = List.of();

    private volatile JobMetrics published = JobMetrics.EMPTY;
    /** The samples a run analysis looks back on: one window and the sample before it. */
    private final java.util.ArrayDeque<JobMetrics> history = new java.util.ArrayDeque<>();

    private @Nullable PipeForgeException failure;
    private @Nullable SplitEnumerator<SourceSplit, ?> enumerator;
    /** Runs every call of the enumerator, one at a time, so that a blocked call can time out. */
    private final ExecutorService enumeratorThread;

    private final Duration enumeratorTimeout;
    private volatile boolean enumeratorStuck;
    /** The splits every reader was given; a restarted reader's splits go back to the enumerator (M2). */
    private final Map<Integer, Set<String>> owners = new ConcurrentHashMap<>();
    /** Splits the readers reported as read to their end. */
    private final Set<String> finishedSplits = ConcurrentHashMap.newKeySet();
    /** The splits the enumerator makes in all, or -1 while it does not know. */
    private volatile int totalSplits = -1;

    private @Nullable Source<SourceSplit, ?> source;

    /** The storage and the metastore of checkpoints, or {@code null} on a master without them. */
    private final org.devlive.pipeforge.engine.master.checkpoint.@Nullable Checkpoints checkpointing;

    /** Takes the checkpoints once the tasks are deployed; {@code null} without checkpoints. */
    private org.devlive.pipeforge.engine.master.checkpoint.@Nullable CheckpointCoordinator checkpoints;

    /** The checkpoint metrics, published with the job metrics. */
    private volatile org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Stats checkpointStats =
            org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Stats.NONE;

    /**
     * @param graph the job graph, from {@link ChainTopology#graph}
     * @param onEnded runs once the run has ended and its slots were given back
     */
    public JobMaster(
            JobId id, JobPlan plan, JobGraph graph, SubmitOptions options, Placement placement, Runnable onEnded) {
        this(id, plan, graph, options, placement, onEnded, null);
    }

    /**
     * @param checkpointing the storage and metastore of checkpoints; without them the run takes none
     */
    public JobMaster(
            JobId id,
            JobPlan plan,
            JobGraph graph,
            SubmitOptions options,
            Placement placement,
            Runnable onEnded,
            org.devlive.pipeforge.engine.master.checkpoint.@Nullable Checkpoints checkpointing) {
        this.checkpointing = checkpointing;
        this.id = id;
        this.plan = plan;
        this.graph = graph;
        this.execution = ExecutionGraph.of(graph);
        this.sourceVertex = graph.vertices().get(0);
        this.sinkVertex = graph.vertices().get(graph.vertices().size() - 1);
        this.parallelism = sourceVertex.parallelism();
        this.job = new JobContext(plan.name(), plan.parallelism(), plan.timeZone());
        this.run = new RunContext(
                id.value(),
                id.value(),
                plan.variables(),
                runStartTime,
                null,
                plan.mode().id(),
                plan.delivery().id());
        for (JobGraph.Vertex vertex : graph.vertices()) {
            deployed.put(vertex.id(), described(chain(vertex), run));
        }
        this.limit = plan.limit();
        this.settings = new TaskSettings(
                        options.asyncWriteBuffer(),
                        options.writeBatchSize(),
                        options.writeQueueCapacity(),
                        options.cancelTimeout(),
                        options.metricsInterval())
                .withSkipDirtyRows(plan.dirty().skips());
        this.placement = placement;
        this.onEnded = onEnded;
        this.expressions = EngineExpressions.forRun(plan.timeZone(), runStartTime);
        this.aggregator = new MetricsAggregator(graph, started);
        this.thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "jobmaster-" + plan.name());
            t.setDaemon(true);
            return t;
        });
        this.enumeratorThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "enumerator-" + plan.name());
            t.setDaemon(true);
            return t;
        });
        this.enumeratorTimeout = options.enumeratorTimeout();
    }

    public JobHandle handle() {
        return handle;
    }

    public String runId() {
        return id.value();
    }

    /**
     * Gets the slots, then starts the enumerator and deploys the tasks. Slots that arrive after the
     * run ended (it was cancelled while it waited) are given back at once.
     */
    @SuppressWarnings("FutureReturnValueIgnored") // the callback posts to the job master thread
    public void start() {
        placement.acquire(id.value(), id.value(), execution.slots()).whenComplete((acquired, error) -> {
            Runnable arrived = () -> {
                if (status.isTerminal()) {
                    placement.release();
                } else if (error != null) {
                    fail(PipeForgeException.wrap(
                            error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                                    ? error.getCause()
                                    : error));
                } else {
                    slots = acquired;
                    deploy();
                }
            };
            if (!tryPost(arrived)) {
                placement.release();
            }
        });
    }

    /**
     * A worker was lost: the tasks that still ran there are gone, and the run fails with {@code
     * error}, scoped to the first of them.
     */
    public void workerLost(String workerId, PipeForgeException error) {
        post(() -> {
            List<ExecutionGraph.Task> gone = new ArrayList<>();
            states.forEach((task, state) -> {
                if (slots.get(task.slot()).workerId().equals(workerId) && !state.isTerminal()) {
                    gone.add(task);
                }
            });
            if (gone.isEmpty()) {
                return;
            }
            PipeForgeException.Builder scoped = PipeForgeException.of(error.code())
                    .scope(ErrorScope.node(gone.get(0).vertex())
                            .withJob(plan.name())
                            .withRun(id.value())
                            .withSubtask(gone.get(0).subtask()))
                    .kind(error.kind());
            error.params().forEach(scoped::param);
            fail(scoped.build());
            gone.forEach(task -> states.put(task, TaskState.FAILED));
            refreshTasks();
            endIfDone();
        });
    }

    public void onTaskState(TaskStateUpdate update) {
        post(() -> handleTaskState(update));
    }

    public void onMetrics(ExecutionAttemptId attempt, TaskMetricsSnapshot metrics) {
        post(() -> {
            ExecutionGraph.Task task = task(attempt);
            TaskState state = states.get(task);
            // a periodic report can arrive after the final one; the final one wins
            if (state != null && !state.isTerminal() && attempt.equals(attempt(task))) {
                aggregator.update(task.vertex(), task.subtask(), metrics);
                checkDirtyRows();
            }
        });
    }

    public void onReaderMessage(ReaderMessage message) {
        post(() -> handleReaderMessage(message));
    }

    public void onCheckpointAck(org.devlive.pipeforge.engine.rpc.CheckpointAck ack) {
        post(() -> {
            if (checkpoints != null) {
                checkpoints.acknowledge(ack);
                checkpointStats = checkpoints.stats();
            }
        });
    }

    public void onCheckpointDecline(ExecutionAttemptId task, long checkpointId, PipeForgeException reason) {
        post(() -> {
            if (checkpoints != null) {
                checkpoints.decline(task, checkpointId, reason);
                checkpointStats = checkpoints.stats();
            }
        });
    }

    /** The checkpoint metrics of the run. */
    public org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Stats checkpointStats() {
        return checkpointStats;
    }

    // ------------------------------------------------------------------ checkpoints

    /** Starts the periodic checkpoints, on a master with checkpoint storage and a job that wants them. */
    @SuppressWarnings("FutureReturnValueIgnored") // the timer runs the action on the job master thread
    private void startCheckpoints() {
        var services = checkpointing;
        if (services == null || !plan.checkpoint().enabled()) {
            return;
        }
        List<org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Node> nodes = new ArrayList<>();
        for (JobGraph.Vertex vertex : graph.vertices()) {
            for (PlannedNode node : vertex.nodes()) {
                nodes.add(new org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Node(
                        node.id(),
                        node.kind().name().toLowerCase(java.util.Locale.ROOT),
                        node.plugin(),
                        vertex.parallelism()));
            }
        }
        var context = new org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Context(
                services.storage(),
                services.store(),
                new org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Tasks() {
                    @Override
                    public List<ExecutionAttemptId> sources() {
                        return execution.tasks().stream()
                                .filter(t -> t.vertex().equals(sourceVertex.id()))
                                .map(JobMaster.this::attempt)
                                .toList();
                    }

                    @Override
                    public List<ExecutionAttemptId> all() {
                        return execution.tasks().stream()
                                .map(JobMaster.this::attempt)
                                .toList();
                    }

                    @Override
                    public boolean running() {
                        return !status.isTerminal()
                                && status != JobStatus.CANCELLING
                                && states.values().stream().allMatch(s -> s == TaskState.RUNNING);
                    }

                    @Override
                    public TaskExecutorGateway gateway(ExecutionAttemptId task) {
                        return JobMaster.this.gateway(task(task));
                    }
                },
                this::snapshotEnumerator,
                (action, delayMs) -> {
                    var scheduled = thread.schedule(() -> run(action), delayMs, TimeUnit.MILLISECONDS);
                    return () -> scheduled.cancel(false);
                },
                this::post,
                services.io(),
                System::currentTimeMillis,
                this::fail);
        try {
            checkpoints = new org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator(
                    plan.checkpoint(),
                    new org.devlive.pipeforge.engine.master.checkpoint.CheckpointCoordinator.Run(
                            id.value(), plan.name(), 1, plan.variables(), DispatcherService.engineVersion(), nodes),
                    context,
                    0);
        } catch (PipeForgeException e) {
            fail(e);
            return;
        }
        checkpoints.start();
    }

    /** The state of the split enumerator, written with the source's serializer. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, SerializedValue> snapshotEnumerator(long checkpointId) {
        SplitEnumerator current = enumerator;
        Source owner = source;
        PlannedNode node = sourceVertex.head();
        if (current == null || owner == null || enumeratorStuck) {
            throw PipeForgeException.of(ErrorCode.CHECKPOINT_FAILED)
                    .param("checkpoint", checkpointId)
                    .param("reason", "the enumerator of " + node.id() + " is not running")
                    .build();
        }
        SerializedValue state = onEnumerator(
                "snapshot the enumerator for checkpoint " + checkpointId,
                () -> SerializedValue.of(owner.enumeratorStateSerializer(), current.snapshotState(checkpointId)));
        return Map.of(node.id(), state);
    }

    public void cancel() {
        post(() -> {
            if (status.isTerminal() || status == JobStatus.CANCELLING) {
                return;
            }
            LOG.info("Cancelling job {} ({})", plan.name(), id);
            status(JobStatus.CANCELLING);
            cancelTasks();
            endIfDone();
        });
    }

    // ------------------------------------------------------------------ lifecycle

    // deployment failures post back; the metrics task runs until the thread stops
    @SuppressWarnings({"unchecked", "FutureReturnValueIgnored"})
    private void deploy() {
        PlannedNode node = sourceVertex.head();
        try {
            SourceFactory factory = (SourceFactory) node.component().factory();
            Source<SourceSplit, ?> created = (Source<SourceSplit, ?>)
                    onEnumerator("create the source", () -> factory.createSource(context(node)));
            source = created;
            SplitEnumerator<SourceSplit, ?> opened =
                    onEnumerator("create the enumerator", () -> created.createEnumerator(new LocalEnumeratorContext()));
            enumerator = opened;
            onEnumerator("start the enumerator", () -> {
                opened.start();
                totalSplits = opened.totalSplits().orElse(-1);
                return null;
            });
        } catch (PipeForgeException e) {
            fail(e);
            return;
        }
        for (ExecutionGraph.Task task : execution.tasks()) {
            states.put(task, TaskState.DEPLOYING);
        }
        // consumers may ask for an output before its producer runs; the worker waits for it
        for (ExecutionGraph.Task task : execution.tasks()) {
            Placement.Slot slot = slots.get(task.slot());
            JobGraph.Vertex vertex = graph.vertex(task.vertex()).orElseThrow();
            TaskDeployment deployment = new TaskDeployment(
                    attempt(task),
                    deployed.get(task.vertex()),
                    vertex.parallelism(),
                    settings,
                    run,
                    slot.allocationId(),
                    inputs(task));
            slot.gateway().deployTask(deployment).whenComplete((ignored, error) -> {
                if (error != null) {
                    post(() -> fail(PipeForgeException.wrap(error)));
                }
            });
        }
        status(JobStatus.RUNNING);
        refreshTasks();
        startCheckpoints();
        long interval = settings.metricsInterval().toMillis();
        thread.scheduleAtFixedRate(() -> run(this::publish), interval, interval, TimeUnit.MILLISECONDS);
        long period = QUOTA_PERIOD.toMillis();
        thread.scheduleAtFixedRate(() -> run(() -> sendQuotas(false)), period, period, TimeUnit.MILLISECONDS);
        LOG.info(
                "Job {} ({}) is running with {} task(s) in {} vertices",
                plan.name(),
                id,
                execution.tasks().size(),
                graph.vertices().size());
    }

    /** The chain of a vertex, with an output for every edge that leaves it. */
    private ChainDescriptor chain(JobGraph.Vertex vertex) {
        List<ChainDescriptor.Output> outputs = new ArrayList<>();
        List<JobGraph.Edge> edges = graph.outputs(vertex.id());
        for (int i = 0; i < edges.size(); i++) {
            JobGraph.Edge edge = edges.get(i);
            int consumers = graph.vertex(edge.target()).orElseThrow().parallelism();
            outputs.add(new ChainDescriptor.Output(
                    i, edge.target(), edge.partitioning(), edge.partitioning().allToAll() ? consumers : 1));
        }
        return new ChainDescriptor(vertex.id(), job, vertex.nodes(), outputs);
    }

    /**
     * The inputs of a task: one channel per upstream task connected to it, local when the producer
     * runs on the same worker.
     */
    private List<TaskDeployment.Input> inputs(ExecutionGraph.Task task) {
        List<TaskDeployment.Input> inputs = new ArrayList<>();
        for (JobGraph.Edge edge : graph.inputs(task.vertex())) {
            int output = graph.outputs(edge.source()).indexOf(edge);
            String worker = slots.get(task.slot()).workerId();
            List<TaskDeployment.Channel> channels = execution.connections().stream()
                    .filter(c -> c.target().equals(task) && c.source().vertex().equals(edge.source()))
                    .sorted(Comparator.comparingInt(c -> c.source().subtask()))
                    .map(c -> {
                        Placement.Slot producer = slots.get(c.source().slot());
                        return new TaskDeployment.Channel(
                                attempt(c.source()),
                                output,
                                c.partitioning().allToAll() ? task.subtask() : 0,
                                producer.workerId().equals(worker) ? null : producer.dataAddress());
                    })
                    .toList();
            inputs.add(new TaskDeployment.Input(edge.input(), edge.source(), channels));
        }
        return inputs;
    }

    private void handleTaskState(TaskStateUpdate update) {
        ExecutionGraph.Task task = task(update.id());
        TaskState previous = states.get(task);
        if (previous == null || previous.isTerminal() || !update.id().equals(attempt(task))) {
            return;
        }
        boolean reader = task.vertex().equals(sourceVertex.id());
        int subtask = task.subtask();
        states.put(task, update.state());
        refreshTasks();
        if (update.metrics() != null) {
            aggregator.update(task.vertex(), subtask, update.metrics());
            checkDirtyRows();
        }
        switch (update.state()) {
            case RUNNING -> {
                if (reader) {
                    readers.add(subtask);
                    // the quota reaches the reader before its first split
                    sendQuotas(false);
                    callEnumerator("register reader " + subtask, e -> e.addReader(subtask));
                    List<ReaderMessage> waiting = early.remove(subtask);
                    if (waiting != null) {
                        waiting.forEach(this::handleReaderMessage);
                    }
                }
            }
            case FINISHED -> {
                committables.addAll(update.committables());
                if (!update.committables().isEmpty()) {
                    aggregator.twoPhase();
                }
            }
            case FAILED -> fail(update.error());
            case CANCELED, DEPLOYING -> {
                // nothing to do until every task has ended
            }
        }
        if (update.state().isTerminal() && reader) {
            readers.remove(subtask);
            early.remove(subtask);
        }
        endIfDone();
    }

    /** The task of an attempt; every task of a vertex runs in the slot of its subtask. */
    private static ExecutionGraph.Task task(ExecutionAttemptId attempt) {
        return new ExecutionGraph.Task(attempt.vertexId(), attempt.subtask(), attempt.subtask());
    }

    private void handleReaderMessage(ReaderMessage message) {
        int subtask = message.task().subtask();
        if (status.isTerminal() || !message.task().equals(reader(subtask))) {
            return;
        }
        if (!readers.contains(subtask)) {
            TaskState state = states.get(readerTask(subtask));
            if (state == TaskState.DEPLOYING) {
                early.computeIfAbsent(subtask, s -> new ArrayList<>()).add(message);
            }
            return;
        }
        switch (message) {
            case ReaderMessage.SplitRequest request -> callEnumerator(
                    "handle a split request", e -> e.handleSplitRequest(subtask));
            case ReaderMessage.Custom custom -> callEnumerator(
                    "handle a reader event", e -> e.handleReaderEvent(subtask, custom.event()));
                // only splits the reader was given count
            case ReaderMessage.SplitsFinished done -> {
                Set<String> owned = owners.getOrDefault(subtask, Set.of());
                done.splitIds().stream().filter(owned::contains).forEach(finishedSplits::add);
            }
        }
    }

    /**
     * Changes the rate limit of the running job; {@link org.devlive.pipeforge.core.plan.RateLimit#NONE}
     * lifts it. The readers get their new quotas at once.
     */
    public void changeRateLimit(org.devlive.pipeforge.core.plan.RateLimit changed) {
        post(() -> {
            LOG.info("Job {} ({}) changes its rate limit to {}", plan.name(), id, changed);
            limit = changed;
            sendQuotas(true);
        });
    }

    /** The rate limit the job runs with now. */
    public org.devlive.pipeforge.core.plan.RateLimit rateLimit() {
        return limit;
    }

    /**
     * Splits the rate limit among the running readers and sends each its quota when it changed by
     * more than a twentieth, or when {@code always}.
     */
    @SuppressWarnings("FutureReturnValueIgnored") // a lost event fails its task, which reports it
    private void sendQuotas(boolean always) {
        if (status.isTerminal() || (!limit.limited() && quotas.isEmpty())) {
            return;
        }
        List<Integer> active = new ArrayList<>(readers);
        quotas.keySet().retainAll(readers);
        Map<Integer, long[]> counters = new HashMap<>();
        for (int reader : active) {
            TaskMetricsSnapshot latest = aggregator.latest(sourceVertex.id(), reader);
            if (latest != null && !latest.operators().isEmpty()) {
                counters.put(reader, new long[] {latest.operators().get(0).rowsIn(), latest.throttleWaitNanos()});
            }
        }
        Set<Integer> throttled = new java.util.HashSet<>();
        Map<Integer, Double> used = usage.since(counters, System.nanoTime(), throttled);
        Map<Integer, Double> rows;
        if (limit.rowsPerSecond() > 0) {
            rows = QuotaAllocator.split(limit.rowsPerSecond(), active, used, throttled);
        } else {
            // without a row limit the shares are equal
            rows = QuotaAllocator.split(active.size(), active, Map.of(), Set.of());
        }
        double rowsTotal =
                rows.values().stream().mapToDouble(Double::doubleValue).sum();
        for (int reader : active) {
            double share = rowsTotal == 0 ? 0 : rows.get(reader) / rowsTotal;
            double[] quota = {limit.rowsPerSecond() > 0 ? rows.get(reader) : 0, limit.bytesPerSecond() * share};
            double[] before = quotas.get(reader);
            if (always || before == null || changed(before[0], quota[0]) || changed(before[1], quota[1])) {
                quotas.put(reader, quota);
                gateway(readerTask(reader))
                        .sendOperatorEvent(
                                reader(reader),
                                new OperatorEvent.RateLimit(sourceVertex.head().id(), quota[0], quota[1]));
            }
        }
        if (!limit.limited()) {
            // the limit was lifted and every reader heard it
            quotas.clear();
        }
    }

    private static boolean changed(double before, double after) {
        return Math.abs(after - before) > Math.max(before, after) / 20;
    }

    /** Fails the job once its tasks skipped more bad rows than {@code env.dirty.max-rows}. */
    private void checkDirtyRows() {
        long skipped = aggregator.rowsDirty();
        if (plan.dirty().exceeded(skipped)) {
            fail(PipeForgeException.of(ErrorCode.TOO_MANY_DIRTY_ROWS)
                    .param("rows", skipped)
                    .param("limit", plan.dirty().maxRows())
                    .scope(ErrorScope.none().withJob(plan.name()).withRun(id.value()))
                    .build());
        }
    }

    /** Records the first failure and cancels the tasks that are still running. */
    private void fail(@Nullable PipeForgeException error) {
        if (failure == null && error != null && !status.isTerminal()) {
            failure = error;
            LOG.warn("Job {} ({}) failed: {}", plan.name(), id, error.getMessage());
            cancelTasks();
        }
        endIfDone();
    }

    @SuppressWarnings("FutureReturnValueIgnored") // each task reports its end itself
    private void cancelTasks() {
        states.forEach((task, state) -> {
            if (!state.isTerminal()) {
                gateway(task).cancelTask(attempt(task));
            }
        });
    }

    private void endIfDone() {
        if (status.isTerminal() || !states.values().stream().allMatch(TaskState::isTerminal)) {
            return;
        }
        if (failure == null && status != JobStatus.CANCELLING) {
            try {
                commit();
            } catch (PipeForgeException e) {
                failure = e;
            }
        }
        closeEnumerator();
        JobStatus end = failure != null
                ? JobStatus.FAILED
                : status == JobStatus.CANCELLING ? JobStatus.CANCELLED : JobStatus.SUCCESS;
        countSplits();
        if (checkpoints != null) {
            checkpoints.stop(end);
            checkpointStats = checkpoints.stats();
        }
        JobMetrics last = withCheckpoints(aggregator.sample(
                System.nanoTime(),
                0,
                end == JobStatus.SUCCESS ? MetricsAggregator.Phase.SUCCEEDED : MetricsAggregator.Phase.STOPPED));
        published = last;
        status(end);
        LOG.info("Job {} ({}) ended: {}", plan.name(), id, end);
        placement.release();
        handle.result.complete(new JobResult(id, end, failure, last));
        thread.shutdown();
        enumeratorThread.shutdownNow();
        onEnded.run();
    }

    // ------------------------------------------------------------------ commit

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commit() {
        if (committables.isEmpty()) {
            return;
        }
        PlannedNode node = sinkVertex.tail();
        SinkFactory factory = (SinkFactory) node.component().factory();
        Sink<Object, Object> sink =
                (Sink<Object, Object>) callSink(node, "create the sink", () -> factory.createSink(context(node)));
        List<Object> values = new ArrayList<>();
        for (SerializedValue value : committables) {
            values.add(callSink(node, "read a committable", () -> value.deserialize(sink.committableSerializer())));
        }
        Optional<SinkCommitter<Object>> committer = callSink(node, "create the committer", sink::createCommitter);
        if (committer.isPresent()) {
            List<Object> pending = values;
            for (int attempt = 1; !pending.isEmpty(); attempt++) {
                List<Object> current = pending;
                pending = callSink(node, "commit", () -> committer.get().commit(current));
                if (!pending.isEmpty() && attempt >= COMMIT_ATTEMPTS) {
                    throw commitFailed(
                            node,
                            pending.size() + " committable(s) still failed after " + COMMIT_ATTEMPTS + " attempts",
                            null);
                }
            }
        }
        Optional<GlobalCommitter<Object, ?>> global =
                callSink(node, "create the global committer", sink::createGlobalCommitter);
        if (global.isPresent()) {
            GlobalCommitter raw = global.get();
            callSink(node, "commit globally", () -> {
                Object combined = raw.combine(values);
                raw.commit(List.of(combined));
                return null;
            });
        }
        aggregator.committed();
        LOG.info("Job {} ({}) committed {} committable(s) of {}", plan.name(), id, values.size(), node.id());
    }

    private <T> T callSink(PlannedNode node, String operation, PluginAction<T> action) {
        try {
            return call(node, action);
        } catch (PipeForgeException e) {
            throw e;
        } catch (Exception e) {
            throw commitFailed(node, operation + ": " + reason(e), e);
        }
    }

    private PipeForgeException commitFailed(PlannedNode node, String reason, @Nullable Throwable cause) {
        return PipeForgeException.of(ErrorCode.COMMIT_FAILED)
                .param("node", node.id())
                .param("reason", reason)
                .scope(scope(node))
                .cause(cause)
                .build();
    }

    // ------------------------------------------------------------------ enumerator

    private interface EnumeratorAction {
        void run(SplitEnumerator<SourceSplit, ?> enumerator) throws Exception;
    }

    private void callEnumerator(String operation, EnumeratorAction action) {
        SplitEnumerator<SourceSplit, ?> current = enumerator;
        if (current == null || enumeratorStuck) {
            return;
        }
        try {
            onEnumerator(operation, () -> {
                action.run(current);
                totalSplits = current.totalSplits().orElse(-1);
                return null;
            });
        } catch (PipeForgeException e) {
            fail(e);
        }
    }

    /**
     * Runs an enumerator call on the enumerator thread and waits for it up to the enumerator
     * timeout. A call that times out is interrupted and fails the job; the enumerator is not
     * called again, since it may still be blocked.
     */
    private <T> T onEnumerator(String operation, PluginAction<T> action) {
        java.util.concurrent.Future<T> call = enumeratorThread.submit(() -> callSource(operation, action));
        try {
            return call.get(enumeratorTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            call.cancel(true);
            enumeratorStuck = true;
            PlannedNode node = sourceVertex.head();
            throw PipeForgeException.of(ErrorCode.ENUMERATOR_TIMEOUT)
                    .param("node", node.id())
                    .param("operation", operation)
                    .param("timeoutMs", enumeratorTimeout.toMillis())
                    .scope(scope(node))
                    .build();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw PipeForgeException.wrap(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            throw PipeForgeException.wrap(e);
        }
    }

    private <T> T callSource(String operation, PluginAction<T> action) {
        PlannedNode node = sourceVertex.head();
        try {
            return call(node, action);
        } catch (PipeForgeException e) {
            throw e.scope().node() != null ? e : withScope(e, scope(node));
        } catch (Exception e) {
            throw PipeForgeException.of(ErrorCode.SOURCE_FAILED)
                    .param("node", node.id())
                    .param("reason", operation + ": " + reason(e))
                    .scope(scope(node))
                    .cause(e)
                    .build();
        }
    }

    private void closeEnumerator() {
        SplitEnumerator<SourceSplit, ?> current = enumerator;
        if (enumeratorStuck) {
            LOG.warn("The enumerator of job {} timed out before; it is not closed", plan.name());
            enumerator = null;
        } else if (current != null) {
            enumerator = null;
            try {
                onEnumerator("close the enumerator", () -> {
                    current.close();
                    return null;
                });
            } catch (PipeForgeException e) {
                LOG.warn("Closing the enumerator of job {} failed", plan.name(), e);
            }
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored") // a lost event fails its task, which reports it
    private void assign(Map<Integer, ? extends List<? extends SourceSplit>> assignment) {
        Source<SourceSplit, ?> owner = source;
        if (owner == null) {
            throw new IllegalStateException("The source is not created");
        }
        assignment.forEach((reader, splits) -> {
            if (reader < 0 || reader >= parallelism) {
                throw new IllegalArgumentException(
                        "Reader " + reader + " does not exist; parallelism is " + parallelism);
            }
            List<SerializedValue> values = new ArrayList<>(splits.size());
            Set<String> owned = owners.computeIfAbsent(reader, r -> ConcurrentHashMap.newKeySet());
            for (SourceSplit split : splits) {
                owned.add(split.splitId());
                values.add(callSource(
                        "write split " + split.splitId(), () -> SerializedValue.of(owner.splitSerializer(), split)));
            }
            gateway(readerTask(reader))
                    .sendOperatorEvent(
                            reader(reader),
                            new OperatorEvent.AddSplits(sourceVertex.head().id(), values));
        });
    }

    /** The enumerator's view of the job; it is only called on the job master thread. */
    private final class LocalEnumeratorContext implements EnumeratorContext<SourceSplit> {
        @Override
        public JobContext job() {
            return job;
        }

        @Override
        public int parallelism() {
            return parallelism;
        }

        @Override
        public Set<Integer> registeredReaders() {
            return Set.copyOf(readers);
        }

        @Override
        public void assignSplits(Map<Integer, List<SourceSplit>> assignment) {
            assign(assignment);
        }

        @Override
        @SuppressWarnings("FutureReturnValueIgnored") // a lost event fails its task, which reports it
        public void signalNoMoreSplits(int readerId) {
            gateway(readerTask(readerId))
                    .sendOperatorEvent(
                            reader(readerId),
                            new OperatorEvent.NoMoreSplits(sourceVertex.head().id()));
        }
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface PluginAction<T> {
        T call() throws Exception;
    }

    private static <T> T call(PlannedNode node, PluginAction<T> action) throws Exception {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(node.component().classLoader());
        try {
            return action.call();
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    private FactoryContext context(PlannedNode node) {
        return new MasterFactoryContext(
                node.id(),
                node.config(),
                job,
                node.inputTables(),
                node.component().classLoader(),
                expressions);
    }

    private ErrorScope scope(PlannedNode node) {
        return ErrorScope.node(node.id()).withJob(plan.name()).withRun(id.value());
    }

    private static PipeForgeException withScope(PipeForgeException error, ErrorScope scope) {
        PipeForgeException.Builder builder = PipeForgeException.of(error.code())
                .scope(scope)
                .kind(error.kind())
                .cause(error.getCause());
        error.params().forEach(builder::param);
        return builder.build();
    }

    private static String reason(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /**
     * The chain as a worker receives it: written in the task description format and read back,
     * with components resolved from the ones of the plan, as a worker resolves them from its
     * plugins. Local runs check the format this way.
     */
    static ChainDescriptor described(ChainDescriptor chain, RunContext run) {
        ChainCodec.Encoded encoded = ChainCodec.encode(chain, run);
        return ChainCodec.decode(encoded.descriptor(), encoded.secrets(), (kind, type, plugin) -> chain.nodes().stream()
                        .filter(n -> n.kind() == kind
                                && n.type().equals(type)
                                && n.plugin().equals(plugin))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("The task description names " + kind.id() + " "
                                + type + " of " + plugin + ", which the plan does not use"))
                        .component())
                .chain();
    }

    /**
     * A split and where it is.
     *
     * @param finished whether the split was read: its reader reported it, or the reader finished
     */
    public record SplitView(String node, String split, int subtask, boolean finished) {}

    /** Every split the enumerator assigned, by reader and split ID; safe from any thread. */
    public List<SplitView> splits() {
        Set<Integer> finished = new java.util.HashSet<>();
        for (TaskView task : taskView) {
            if (task.attempt().vertexId().equals(sourceVertex.id()) && task.state() == TaskState.FINISHED) {
                finished.add(task.attempt().subtask());
            }
        }
        List<SplitView> splits = new ArrayList<>();
        String node = sourceVertex.head().id();
        splitOwners().forEach((reader, owned) -> owned.stream()
                .sorted()
                .forEach(split -> splits.add(new SplitView(
                        node, split, reader, finished.contains(reader) || finishedSplits.contains(split)))));
        return splits;
    }

    /**
     * Reads the log of a task of this run from the worker it ran on; safe from any thread.
     *
     * @throws PipeForgeException {@link ErrorCode#TASK_LOG_NOT_FOUND} for a task this run does not have
     */
    public java.util.concurrent.CompletableFuture<Void> readLog(
            ExecutionAttemptId attempt,
            org.devlive.pipeforge.engine.rpc.LogQuery query,
            java.util.function.Consumer<org.devlive.pipeforge.engine.rpc.LogChunk> chunks) {
        TaskView task = taskView.stream()
                .filter(t -> t.attempt().equals(attempt))
                .findFirst()
                .orElse(null);
        List<Placement.Slot> current = slots;
        if (task == null || attempt.subtask() >= current.size()) {
            throw PipeForgeException.of(ErrorCode.TASK_LOG_NOT_FOUND)
                    .param("task", attempt.toString())
                    .param("worker", task == null ? "-" : task.workerId())
                    .build();
        }
        // subtask i of every vertex runs in slot i
        return current.get(attempt.subtask()).gateway().readLog(attempt, query, chunks);
    }

    /** The splits every reader was given, by reader. */
    public Map<Integer, Set<String>> splitOwners() {
        Map<Integer, Set<String>> copy = new java.util.TreeMap<>();
        owners.forEach((reader, splits) -> copy.put(reader, Set.copyOf(splits)));
        return copy;
    }

    /**
     * A task of the run.
     *
     * @param workerId where it runs, or {@code local}
     */
    public record TaskView(ExecutionAttemptId attempt, TaskState state, String workerId) {}

    /** The error the run failed with, or {@code null}; read it on the job master thread. */
    @Nullable
    PipeForgeException failure() {
        return failure;
    }

    /** The tasks of the run with their states; empty before they are deployed. */
    public List<TaskView> tasks() {
        return taskView;
    }

    /** Learns every change of the run's status, on the job master thread; set before {@link #start}. */
    public void onStatus(java.util.function.Consumer<JobStatus> listener) {
        this.statusListener = listener;
    }

    private void status(JobStatus next) {
        status = next;
        try {
            statusListener.accept(next);
        } catch (RuntimeException e) {
            LOG.warn("A status listener of job {} failed", plan.name(), e);
        }
    }

    /** The tasks in the order of the execution graph: vertex by vertex, then by subtask. */
    private void refreshTasks() {
        List<TaskView> view = new ArrayList<>();
        states.forEach((task, state) -> view.add(new TaskView(
                attempt(task),
                state,
                task.slot() < slots.size() ? slots.get(task.slot()).workerId() : "")));
        taskView = List.copyOf(view);
    }

    private TaskExecutorGateway gateway(ExecutionGraph.Task task) {
        return slots.get(task.slot()).gateway();
    }

    private ExecutionAttemptId attempt(ExecutionGraph.Task task) {
        return new ExecutionAttemptId(id.value(), task.vertex(), task.subtask(), 0);
    }

    private ExecutionGraph.Task readerTask(int subtask) {
        return new ExecutionGraph.Task(sourceVertex.id(), subtask, subtask);
    }

    /** The attempt of reader {@code subtask}: the task of the source vertex. */
    private ExecutionAttemptId reader(int subtask) {
        return attempt(readerTask(subtask));
    }

    /**
     * Gives the aggregator the splits in all (those the enumerator makes, once it says, else those
     * assigned so far) and the splits read: reported by their readers, or of readers that finished.
     */
    private void countSplits() {
        int assigned = 0;
        int finished = 0;
        for (Map.Entry<Integer, Set<String>> owned : owners.entrySet()) {
            assigned += owned.getValue().size();
            boolean readerDone = states.get(readerTask(owned.getKey())) == TaskState.FINISHED;
            for (String split : owned.getValue()) {
                if (readerDone || finishedSplits.contains(split)) {
                    finished++;
                }
            }
        }
        aggregator.splits(Math.max(assigned, totalSplits), finished);
    }

    /** Publishes the current metrics; runs once per metrics interval while the job runs. */
    private void publish() {
        countSplits();
        if (!status.isTerminal()) {
            int running = (int)
                    states.values().stream().filter(s -> s == TaskState.RUNNING).count();
            if (checkpoints != null) {
                checkpointStats = checkpoints.stats();
            }
            published = withCheckpoints(aggregator.sample(System.nanoTime(), running, MetricsAggregator.Phase.RUNNING));
            remember(published);
        }
    }

    private JobMetrics withCheckpoints(JobMetrics metrics) {
        var stats = checkpointStats;
        return metrics.withCheckpoints(
                stats.lastCompletedId(), stats.lastDurationMs(), stats.lastSize(), stats.failures());
    }

    private void remember(JobMetrics sample) {
        synchronized (history) {
            history.addLast(sample);
            // keep the newest sample that is at least a window old, as the base of the window
            while (history.size() > 2) {
                JobMetrics second = history.stream().skip(1).findFirst().orElseThrow();
                if (second.elapsedMs() > sample.elapsedMs() - org.devlive.pipeforge.engine.api.RunAnalyzer.WINDOW_MS) {
                    break;
                }
                history.removeFirst();
            }
        }
    }

    /**
     * The run analysis: of the last {@link org.devlive.pipeforge.engine.api.RunAnalyzer#WINDOW_MS}
     * while the run is running, of the whole run once it ended.
     */
    public org.devlive.pipeforge.engine.api.RunAnalysis analysis() {
        JobStatus now = status;
        JobMetrics current = published;
        List<JobMetrics> samples;
        synchronized (history) {
            samples = new ArrayList<>(history);
        }
        if (samples.isEmpty() || samples.get(samples.size() - 1) != current) {
            samples.add(current);
        }
        return org.devlive.pipeforge.engine.api.RunAnalyzer.analyze(plan.name(), now, samples);
    }

    /** The metrics published last: at most one metrics interval old while running, final once ended. */
    private JobMetrics metrics() {
        return published;
    }

    private void post(Runnable action) {
        tryPost(action);
    }

    /** Runs {@code action} on the job master thread; {@code false} when the run has ended. */
    private boolean tryPost(Runnable action) {
        try {
            thread.execute(() -> run(action));
            return true;
        } catch (RejectedExecutionException e) {
            // the job has ended; late messages are dropped
            return false;
        }
    }

    /** Runs an event of the job master; an unexpected exception fails the job. */
    private void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.error("Job master of {} failed", plan.name(), e);
            fail(PipeForgeException.wrap(e));
        }
    }

    private record MasterFactoryContext(
            String nodeName,
            ReadonlyConfig options,
            JobContext job,
            List<CatalogTable> inputTables,
            ClassLoader classLoader,
            ExpressionCompiler expressions)
            implements FactoryContext {}

    private final class Handle implements JobHandle {
        private final CompletableFuture<JobResult> result = new CompletableFuture<>();

        @Override
        public JobId id() {
            return id;
        }

        @Override
        public String name() {
            return plan.name();
        }

        @Override
        public JobStatus status() {
            return status;
        }

        @Override
        public JobMetrics metrics() {
            return JobMaster.this.metrics();
        }

        @Override
        public CompletableFuture<JobResult> result() {
            return result.copy();
        }

        @Override
        public void cancel() {
            JobMaster.this.cancel();
        }
    }
}
