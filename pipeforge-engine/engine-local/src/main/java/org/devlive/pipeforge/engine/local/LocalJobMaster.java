/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
 * The job master of one job in local mode: runs the split enumerator, deploys the tasks, follows
 * their states and decides how the job ends. Everything it does runs on its own thread, one event at
 * a time, so the enumerator and the job state are never touched concurrently.
 *
 * <ul>
 *   <li>Readers pull splits: a split request from a task reaches {@code handleSplitRequest}, and
 *       the splits the enumerator assigns travel to the task serialized, as they would over the
 *       network.
 *   <li>When every task has finished, the committables of the sink are committed (by its
 *       committer, then its global committer) and the job ends with {@code SUCCESS}.
 *   <li>The first failure cancels the other tasks; the job ends with {@code FAILED} and that error.
 *       There is no restart in local mode.
 * </ul>
 */
final class LocalJobMaster {

    private static final Logger LOG = LoggerFactory.getLogger(LocalJobMaster.class);
    private static final int COMMIT_ATTEMPTS = 3;

    private final JobId id;
    private final JobPlan plan;
    private final ChainDescriptor chain;
    /** What the tasks receive: {@link #chain} written and read in the task description format. */
    private final ChainDescriptor deployed;

    private final RunContext run;

    private final int parallelism;
    private final TaskSettings settings;
    private final TaskExecutorGateway executor;
    private final ScheduledExecutorService thread;
    private final MetricsAggregator aggregator;
    private final Handle handle = new Handle();
    private final long started = System.nanoTime();
    private final Instant runStartTime = Instant.now();
    private final ExpressionCompiler expressions;
    private final Map<Integer, TaskState> states = new HashMap<>();
    private final Set<Integer> readers = new LinkedHashSet<>();
    private final List<SerializedValue> committables = new ArrayList<>();
    private final Runnable onEnded;
    private volatile JobStatus status = JobStatus.CREATED;
    private volatile JobMetrics published = JobMetrics.EMPTY;
    private @Nullable PipeForgeException failure;
    private @Nullable SplitEnumerator<SourceSplit, ?> enumerator;
    private @Nullable Source<SourceSplit, ?> source;

    LocalJobMaster(
            JobId id,
            JobPlan plan,
            List<PlannedNode> nodes,
            SubmitOptions options,
            TaskExecutorGateway executor,
            Runnable onEnded) {
        this.id = id;
        this.plan = plan;
        this.parallelism = nodes.get(0).parallelism();
        this.chain = new ChainDescriptor(
                nodes.get(0).id(), new JobContext(plan.name(), plan.parallelism(), plan.timeZone()), nodes);
        this.run = new RunContext(
                id.value(),
                id.value(),
                plan.variables(),
                runStartTime,
                null,
                plan.mode().id(),
                plan.delivery().id());
        this.deployed = described(chain, run);
        this.settings = new TaskSettings(
                options.asyncWriteBuffer(),
                options.writeBatchSize(),
                options.writeQueueCapacity(),
                options.cancelTimeout(),
                options.metricsInterval());
        this.executor = executor;
        this.onEnded = onEnded;
        this.expressions = EngineExpressions.forRun(plan.timeZone(), runStartTime);
        this.aggregator = new MetricsAggregator(chain, parallelism, started);
        this.thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "jobmaster-" + plan.name());
            t.setDaemon(true);
            return t;
        });
    }

    JobHandle handle() {
        return handle;
    }

    String runId() {
        return id.value();
    }

    void start() {
        post(this::deploy);
    }

    void onTaskState(TaskStateUpdate update) {
        post(() -> handleTaskState(update));
    }

    void onMetrics(ExecutionAttemptId task, TaskMetricsSnapshot metrics) {
        post(() -> {
            int subtask = task.subtask();
            TaskState state = states.get(subtask);
            // a periodic report can arrive after the final one; the final one wins
            if (state != null && !state.isTerminal() && task.equals(attempt(subtask))) {
                aggregator.update(subtask, metrics);
            }
        });
    }

    void onReaderMessage(ReaderMessage message) {
        post(() -> handleReaderMessage(message));
    }

    void cancel() {
        post(() -> {
            if (status.isTerminal() || status == JobStatus.CANCELLING) {
                return;
            }
            LOG.info("Cancelling job {} ({})", plan.name(), id);
            status = JobStatus.CANCELLING;
            cancelTasks();
            endIfDone();
        });
    }

    // ------------------------------------------------------------------ lifecycle

    @SuppressWarnings("unchecked")
    private void deploy() {
        PlannedNode node = chain.source();
        try {
            SourceFactory factory = (SourceFactory) node.component().factory();
            Source<SourceSplit, ?> created =
                    (Source<SourceSplit, ?>) callSource("create the source", () -> factory.createSource(context(node)));
            source = created;
            SplitEnumerator<SourceSplit, ?> opened =
                    callSource("create the enumerator", () -> created.createEnumerator(new LocalEnumeratorContext()));
            enumerator = opened;
            callSource("start the enumerator", () -> {
                opened.start();
                return null;
            });
        } catch (PipeForgeException e) {
            fail(e);
            return;
        }
        for (int subtask = 0; subtask < parallelism; subtask++) {
            states.put(subtask, TaskState.DEPLOYING);
            TaskDeployment deployment = new TaskDeployment(attempt(subtask), deployed, parallelism, settings, run);
            executor.deployTask(deployment).whenComplete((ignored, error) -> {
                if (error != null) {
                    post(() -> fail(PipeForgeException.wrap(error)));
                }
            });
        }
        status = JobStatus.RUNNING;
        long interval = settings.metricsInterval().toMillis();
        thread.scheduleAtFixedRate(() -> run(this::publish), interval, interval, TimeUnit.MILLISECONDS);
        LOG.info("Job {} ({}) is running with {} task(s)", plan.name(), id, parallelism);
    }

    private void handleTaskState(TaskStateUpdate update) {
        int subtask = update.id().subtask();
        TaskState previous = states.get(subtask);
        if (previous == null || previous.isTerminal() || !update.id().equals(attempt(subtask))) {
            return;
        }
        states.put(subtask, update.state());
        if (update.metrics() != null) {
            aggregator.update(subtask, update.metrics());
        }
        switch (update.state()) {
            case RUNNING -> {
                readers.add(subtask);
                callEnumerator("register reader " + subtask, e -> e.addReader(subtask));
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
        if (update.state().isTerminal()) {
            readers.remove(subtask);
        }
        endIfDone();
    }

    private void handleReaderMessage(ReaderMessage message) {
        int subtask = message.task().subtask();
        if (status.isTerminal() || !readers.contains(subtask) || !message.task().equals(attempt(subtask))) {
            return;
        }
        switch (message) {
            case ReaderMessage.SplitRequest request -> callEnumerator(
                    "handle a split request", e -> e.handleSplitRequest(subtask));
            case ReaderMessage.Custom custom -> callEnumerator(
                    "handle a reader event", e -> e.handleReaderEvent(subtask, custom.event()));
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

    private void cancelTasks() {
        states.forEach((subtask, state) -> {
            if (!state.isTerminal()) {
                executor.cancelTask(attempt(subtask));
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
        JobMetrics last = aggregator.sample(
                System.nanoTime(),
                0,
                end == JobStatus.SUCCESS ? MetricsAggregator.Phase.SUCCEEDED : MetricsAggregator.Phase.STOPPED);
        published = last;
        status = end;
        LOG.info("Job {} ({}) ended: {}", plan.name(), id, end);
        handle.result.complete(new JobResult(id, end, failure, last));
        thread.shutdown();
        onEnded.run();
    }

    // ------------------------------------------------------------------ commit

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commit() {
        if (committables.isEmpty()) {
            return;
        }
        PlannedNode node = chain.sink();
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
        if (current == null) {
            return;
        }
        try {
            callSource(operation, () -> {
                action.run(current);
                return null;
            });
        } catch (PipeForgeException e) {
            fail(e);
        }
    }

    private <T> T callSource(String operation, PluginAction<T> action) {
        PlannedNode node = chain.source();
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
        if (current != null) {
            enumerator = null;
            try {
                callSource("close the enumerator", () -> {
                    current.close();
                    return null;
                });
            } catch (PipeForgeException e) {
                LOG.warn("Closing the enumerator of job {} failed", plan.name(), e);
            }
        }
    }

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
            for (SourceSplit split : splits) {
                values.add(callSource(
                        "write split " + split.splitId(), () -> SerializedValue.of(owner.splitSerializer(), split)));
            }
            executor.sendOperatorEvent(
                    attempt(reader), new OperatorEvent.AddSplits(chain.source().id(), values));
        });
    }

    /** The enumerator's view of the job; it is only called on the job master thread. */
    private final class LocalEnumeratorContext implements EnumeratorContext<SourceSplit> {
        @Override
        public JobContext job() {
            return chain.job();
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
        public void signalNoMoreSplits(int readerId) {
            executor.sendOperatorEvent(
                    attempt(readerId),
                    new OperatorEvent.NoMoreSplits(chain.source().id()));
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
                chain.job(),
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

    private ExecutionAttemptId attempt(int subtask) {
        return new ExecutionAttemptId(id.value(), chain.vertexId(), subtask, 0);
    }

    /** Publishes the current metrics; runs once per metrics interval while the job runs. */
    private void publish() {
        if (!status.isTerminal()) {
            int running = (int)
                    states.values().stream().filter(s -> s == TaskState.RUNNING).count();
            published = aggregator.sample(System.nanoTime(), running, MetricsAggregator.Phase.RUNNING);
        }
    }

    /** The metrics published last: at most one metrics interval old while running, final once ended. */
    private JobMetrics metrics() {
        return published;
    }

    private void post(Runnable action) {
        try {
            thread.execute(() -> run(action));
        } catch (RejectedExecutionException e) {
            // the job has ended; late messages are dropped
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
            return LocalJobMaster.this.metrics();
        }

        @Override
        public CompletableFuture<JobResult> result() {
            return result.copy();
        }

        @Override
        public void cancel() {
            LocalJobMaster.this.cancel();
        }
    }
}
