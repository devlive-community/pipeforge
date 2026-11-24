/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.master.RunStates.State;
import org.devlive.pipeforge.engine.rpc.InProcessRpc;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.devlive.pipeforge.engine.worker.TaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Runs accepted, followed, cancelled, timed out and listed by the dispatcher, on a local task executor. */
class DispatcherTest {

    final JobMasters jobs = new JobMasters();
    final InProcessRpc.Endpoint<JobMasterGateway> master = InProcessRpc.serve("master", jobs);
    final java.nio.file.Path logDir = temporaryLogs();
    final org.devlive.pipeforge.engine.worker.log.LogService logs =
            new org.devlive.pipeforge.engine.worker.log.LogService(
                    "local", org.devlive.pipeforge.engine.worker.log.LogService.Settings.defaults(logDir));
    final TaskExecutor executor = new TaskExecutor("local", master.gateway(), null, null, logs);

    private static java.nio.file.Path temporaryLogs() {
        try {
            return java.nio.file.Files.createTempDirectory("dispatcher-logs");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    final InProcessRpc.Endpoint<TaskExecutorGateway> worker = InProcessRpc.serve("worker", executor);
    final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    final Map<String, Supplier<JobPlan>> definitions = new ConcurrentHashMap<>();
    final AtomicInteger planned = new AtomicInteger();
    final List<String> released = new CopyOnWriteArrayList<>();
    Dispatcher dispatcher = dispatcher(100);

    Dispatcher dispatcher(int retained) {
        return dispatcher(retained, SubmitOptions.defaults());
    }

    Dispatcher dispatcher(int retained, SubmitOptions options) {
        return new Dispatcher(
                submission -> {
                    planned.incrementAndGet();
                    Supplier<JobPlan> plan = definitions.get(submission.definition());
                    if (plan == null) {
                        throw PipeForgeException.of(ErrorCode.JOB_INVALID)
                                .param("location", "definition")
                                .param("reason", "unknown definition " + submission.definition())
                                .build();
                    }
                    return new Dispatcher.Planned(plan.get(), () -> released.add(submission.runId()));
                },
                plan -> Placement.local(worker.gateway()),
                jobs,
                options,
                timers,
                Clock.systemUTC(),
                retained);
    }

    @AfterEach
    void stop() {
        logs.close();
        dispatcher.close();
        executor.close();
        worker.close();
        master.close();
        timers.shutdownNow();
    }

    static Dispatcher.Submission submission(String run, String definition, Duration timeout) {
        return new Dispatcher.Submission("job-1", run, definition, Map.of(), null, Map.of(), timeout);
    }

    Dispatcher.RunStatus awaitEnd(String run) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            Dispatcher.RunStatus status = dispatcher.status(run);
            if (status.state().isTerminal() && dispatcher.result(run).isPresent()) {
                return status;
            }
            assertThat(System.nanoTime()).as("run %s ends", run).isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    String ok() {
        String probe = Probe.create();
        definitions.put("ok", () -> TestConnectors.job(probe, "rows: 20, splits: 2", "", "", 1));
        return probe;
    }

    String blocking() {
        String probe = Probe.create();
        definitions.put("block", () -> TestConnectors.job(probe, "block: cancel", null, "", 1));
        return probe;
    }

    @Test
    void followsARunFromSubmissionToSuccess() throws Exception {
        ok();
        Dispatcher.RunStatus accepted = dispatcher.submit(submission("run-1", "ok", null));
        assertThat(accepted.runId()).isEqualTo("run-1");
        assertThat(accepted.jobName()).isEqualTo("test-job");

        Dispatcher.RunStatus end = awaitEnd("run-1");
        assertThat(end.state()).isEqualTo(State.SUCCESS);
        assertThat(end.error()).isNull();
        assertThat(end.transitions())
                .extracting(RunStates.Transition::to)
                .containsExactly(State.QUEUE, State.RUNNING, State.SUCCESS);
        assertThat(end.transitions().get(1).reason()).isEqualTo("tasks deployed");
        assertThat(end.ended()).isAfterOrEqualTo(end.started());
        assertThat(end.tasks()).singleElement().satisfies(task -> {
            assertThat(task.state()).isEqualTo(TaskState.FINISHED);
            assertThat(task.workerId()).isEqualTo("local");
            assertThat(task.attempt().runId()).isEqualTo("run-1");
        });
        assertThat(dispatcher.metrics("run-1").rowsWritten()).isEqualTo(20);
        assertThat(released).containsExactly("run-1");
    }

    @Test
    void aRunIdIsStartedOnce() throws Exception {
        ok();
        dispatcher.submit(submission("run-1", "ok", null));
        Dispatcher.RunStatus again = dispatcher.submit(submission("run-1", "ok", null));
        assertThat(again.runId()).isEqualTo("run-1");
        assertThat(planned).hasValue(1);
        awaitEnd("run-1");
        assertThat(dispatcher.list(Set.of(), 10)).hasSize(1);
    }

    @Test
    void cancelsARun() throws Exception {
        String probe = blocking();
        dispatcher.submit(submission("run-1", "block", null));
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();

        dispatcher.cancel("run-1", "operator asked");
        Dispatcher.RunStatus end = awaitEnd("run-1");
        assertThat(end.state()).isEqualTo(State.CANCELLED);
        assertThat(end.transitions())
                .extracting(RunStates.Transition::to)
                .containsExactly(State.QUEUE, State.RUNNING, State.CANCELLING, State.CANCELLED);
        assertThat(end.transitions().get(2).reason()).isEqualTo("cancel requested");
        assertThat(dispatcher.cancel("run-1", "again").state())
                .as("an ended run stays")
                .isEqualTo(State.CANCELLED);
    }

    @Test
    void aRunPastItsTimeoutEndsWithTimeout() throws Exception {
        blocking();
        dispatcher.submit(submission("run-1", "block", Duration.ofMillis(300)));
        Dispatcher.RunStatus end = awaitEnd("run-1");
        assertThat(end.state()).isEqualTo(State.TIMEOUT);
        assertThat(end.transitions()).extracting(RunStates.Transition::to).endsWith(State.CANCELLING, State.TIMEOUT);
        assertThat(end.transitions().get(end.transitions().size() - 2).reason()).isEqualTo("timeout of PT0.3S");
    }

    @Test
    void aFailedRunKeepsItsError() throws Exception {
        String probe = Probe.create();
        definitions.put("fail", () -> TestConnectors.job(probe, "rows: 100, splits: 2", "fail-at: 5", "", 1));
        dispatcher.submit(submission("run-1", "fail", null));
        Dispatcher.RunStatus end = awaitEnd("run-1");
        assertThat(end.state()).isEqualTo(State.FAILED);
        assertThat(end.error()).isNotNull();
        assertThat(end.error().code().id()).isEqualTo("PF-PLUG-0007");
    }

    @Test
    void unknownRunsAndDefinitionsAreRefused() {
        for (var call : List.<Runnable>of(
                () -> dispatcher.status("nope"),
                () -> dispatcher.cancel("nope", "x"),
                () -> dispatcher.metrics("nope"))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.code())
                    .isEqualTo(ErrorCode.RUN_NOT_FOUND));
        }
        assertThatThrownBy(() -> dispatcher.submit(submission("run-1", "missing", null)))
                .isInstanceOfSatisfying(
                        PipeForgeException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.JOB_INVALID));
        assertThat(dispatcher.list(Set.of(), 10)).isEmpty();
    }

    @Test
    void listsRunsNewestFirstAndKeepsOnlySomeThatEnded() throws Exception {
        dispatcher = dispatcher(2);
        ok();
        for (int i = 1; i <= 4; i++) {
            dispatcher.submit(submission("run-" + i, "ok", null));
            awaitEnd("run-" + i);
            Thread.sleep(2);
        }
        String probe = blocking();
        dispatcher.submit(submission("run-5", "block", null));
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(dispatcher.list(Set.of(), 10))
                .extracting(Dispatcher.RunStatus::runId)
                .containsExactly("run-5", "run-4", "run-3");
        assertThat(dispatcher.list(Set.of(State.RUNNING), 10))
                .extracting(Dispatcher.RunStatus::runId)
                .containsExactly("run-5");
        assertThat(dispatcher.list(Set.of(), 1)).hasSize(1);
        dispatcher.cancel("run-5", "done");
        awaitEnd("run-5");
    }

    @Test
    void concurrentSubmissionsAndCancelsEndConsistently() throws Exception {
        ok();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                String run = "run-" + i;
                boolean cancel = i % 3 == 0;
                calls.add(pool.submit(() -> {
                    dispatcher.submit(submission(run, "ok", null));
                    dispatcher.submit(submission(run, "ok", null));
                    if (cancel) {
                        dispatcher.cancel(run, "test");
                    }
                    return null;
                }));
            }
            for (Future<?> call : calls) {
                call.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(planned).as("each run planned once").hasValue(24);
        for (int i = 0; i < 24; i++) {
            Dispatcher.RunStatus end = awaitEnd("run-" + i);
            assertThat(end.state()).isIn(State.SUCCESS, State.CANCELLED);
            List<RunStates.Transition> transitions = end.transitions();
            for (int t = 1; t < transitions.size(); t++) {
                assertThat(RunStates.allowed(
                                transitions.get(t - 1).to(), transitions.get(t).to()))
                        .as("run-%d: %s", i, transitions)
                        .isTrue();
            }
        }
        assertThat(released).hasSize(24).doesNotHaveDuplicates();
    }

    @Test
    void theRateLimitOfARunningRunCanBeChanged() throws Exception {
        String probe = Probe.create();
        // 300 rows at 20 per second would take 15 seconds
        definitions.put(
                "slow",
                () -> TestConnectors.job(probe, "rows: 300, splits: 3", null, "", 1, "limit: { rows-per-second: 20 }"));
        dispatcher.submit(submission("run-1", "slow", null));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (dispatcher.status("run-1").state() != State.RUNNING
                || dispatcher.metrics("run-1").rowsRead() == 0) {
            assertThat(System.nanoTime()).as("the run starts reading").isLessThan(deadline);
            Thread.sleep(5);
        }
        long changed = System.nanoTime();
        dispatcher.changeRateLimit("run-1", org.devlive.pipeforge.core.plan.RateLimit.NONE);

        assertThat(awaitEnd("run-1").state()).isEqualTo(State.SUCCESS);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - changed))
                .as("lifted, the rest is read at once")
                .isLessThan(5);
        assertThat(Probe.get(probe).rows).hasSize(300);
        assertThat(dispatcher
                        .changeRateLimit("run-1", new org.devlive.pipeforge.core.plan.RateLimit(5, 0))
                        .state())
                .as("a run that ended stays as it is")
                .isEqualTo(State.SUCCESS);
    }

    @Test
    void aPausedRunIsResumedBySubmittingItAgainFromItsSavepoint(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        dispatcher.close();
        // a short write queue, so that the savepoint does not wait behind most of the input
        dispatcher = dispatcher(
                        100, SubmitOptions.defaults().withWriteBatchSize(10).withWriteQueueCapacity(20))
                .withCheckpoints(new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(
                        new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir),
                        new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore(),
                        Runnable::run));
        String probe = Probe.create();
        definitions.put(
                "slow",
                () -> TestConnectors.job(probe, "rows: 2000, splits: 4", null, "commit: transactional, slow-ms: 1", 1));
        dispatcher.submit(submission("run-1", "slow", null));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (dispatcher.status("run-1").state() != State.RUNNING
                || dispatcher.status("run-1").tasks().stream().anyMatch(t -> t.state() != TaskState.RUNNING)) {
            assertThat(System.nanoTime()).as("the run runs").isLessThan(deadline);
            Thread.sleep(5);
        }
        Thread.sleep(100);

        var savepoint = dispatcher.pause("run-1", "nightly").get(20, TimeUnit.SECONDS);
        while (dispatcher.status("run-1").state() != State.PAUSED) {
            assertThat(System.nanoTime()).as("the run pauses").isLessThan(deadline + TimeUnit.SECONDS.toNanos(10));
            Thread.sleep(5);
        }
        assertThat(dispatcher.submit(submission("run-1", "slow", null)).state())
                .as("submitting it again without a restore point leaves it paused")
                .isEqualTo(State.PAUSED);

        dispatcher.submit(new Dispatcher.Submission(
                "job-1",
                "run-1",
                "slow",
                Map.of(),
                null,
                Map.of(),
                null,
                new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                        savepoint.directory(), false, false)));
        awaitEnd("run-1");
        var status = dispatcher.status("run-1");
        assertThat(status.state()).isEqualTo(State.SUCCESS);
        assertThat(status.transitions())
                .extracting(RunStates.Transition::to)
                .containsExactly(
                        State.QUEUE,
                        State.RUNNING,
                        State.PAUSING,
                        State.PAUSED,
                        State.QUEUE,
                        State.RUNNING,
                        State.SUCCESS);
        assertThat(status.transitions().get(4).reason()).startsWith("resumed from run-1/savepoints/nightly-");
        List<Long> ids = Probe.get(probe).rows.stream()
                .map(r -> (Long) r.get(0))
                .sorted()
                .toList();
        assertThat(ids).hasSize(2000).doesNotHaveDuplicates();
    }

    @Test
    void aPausedRunThatIsCancelledEndsWithoutRunning(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        dispatcher.close();
        dispatcher = dispatcher(100)
                .withCheckpoints(new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(
                        new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir),
                        new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore(),
                        Runnable::run));
        String probe = Probe.create();
        definitions.put("endless", () -> TestConnectors.job(probe, "endless: true", null, "", 1));
        dispatcher.submit(submission("run-2", "endless", null));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (dispatcher.status("run-2").tasks().stream().noneMatch(t -> t.state() == TaskState.RUNNING)) {
            assertThat(System.nanoTime()).as("the run runs").isLessThan(deadline);
            Thread.sleep(5);
        }
        dispatcher.pause("run-2", null).get(20, TimeUnit.SECONDS);
        while (dispatcher.status("run-2").state() != State.PAUSED) {
            Thread.sleep(5);
        }
        assertThat(dispatcher.cancel("run-2", "not needed").state()).isEqualTo(State.CANCELLED);
    }

    Dispatcher withCheckpoints(java.nio.file.Path dir) {
        dispatcher.close();
        // a short write queue, so that a savepoint does not wait behind most of the input
        return dispatcher(100, SubmitOptions.defaults().withWriteBatchSize(10).withWriteQueueCapacity(20))
                .withCheckpoints(new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(
                        new org.devlive.pipeforge.engine.api.checkpoint.FileSystemCheckpointStorage(dir),
                        new org.devlive.pipeforge.engine.master.checkpoint.MemoryCheckpointStore(),
                        Runnable::run));
    }

    static long between(Dispatcher.RunStatus status, int from, int to) {
        return Duration.between(
                        status.transitions().get(from).at(),
                        status.transitions().get(to).at())
                .toMillis();
    }

    @Test
    void aRunPastTheRunTimeoutOfItsJobEndsWithTimeout() throws Exception {
        String probe = Probe.create();
        definitions.put(
                "block", () -> TestConnectors.job(probe, "block: cancel", null, "", 1, "timeout: { run: 300ms }"));
        dispatcher.submit(submission("run-1", "block", null));
        Dispatcher.RunStatus end = awaitEnd("run-1");
        assertThat(end.state()).isEqualTo(State.TIMEOUT);
        assertThat(end.transitions())
                .extracting(RunStates.Transition::to)
                .containsExactly(State.QUEUE, State.RUNNING, State.CANCELLING, State.TIMEOUT);
        assertThat(end.transitions().get(2).reason()).isEqualTo("timeout of PT0.3S");
        assertThat(between(end, 1, 2)).as("counted from RUNNING").isGreaterThanOrEqualTo(250);
    }

    @Test
    void theTimeARunIsPausedDoesNotCount(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        dispatcher = withCheckpoints(dir);
        String probe = Probe.create();
        definitions.put(
                "endless", () -> TestConnectors.job(probe, "endless: true", null, "", 1, "timeout: { run: 3s }"));
        dispatcher.submit(submission("run-1", "endless", null));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (dispatcher.status("run-1").tasks().stream().noneMatch(t -> t.state() == TaskState.RUNNING)) {
            assertThat(System.nanoTime()).as("the run runs").isLessThan(deadline);
            Thread.sleep(5);
        }
        var savepoint = dispatcher.pause("run-1", null).get(20, TimeUnit.SECONDS);
        while (dispatcher.status("run-1").state() != State.PAUSED) {
            Thread.sleep(5);
        }
        Thread.sleep(3500);
        assertThat(dispatcher.status("run-1").state())
                .as("not timed out while paused")
                .isEqualTo(State.PAUSED);

        dispatcher.submit(new Dispatcher.Submission(
                "job-1",
                "run-1",
                "endless",
                Map.of(),
                null,
                Map.of(),
                null,
                new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                        savepoint.directory(), false, false)));
        Dispatcher.RunStatus end = awaitEnd("run-1");
        assertThat(end.state()).isEqualTo(State.TIMEOUT);
        assertThat(end.transitions())
                .extracting(RunStates.Transition::to)
                .containsExactly(
                        State.QUEUE,
                        State.RUNNING,
                        State.PAUSING,
                        State.PAUSED,
                        State.QUEUE,
                        State.RUNNING,
                        State.CANCELLING,
                        State.TIMEOUT);
        long before = between(end, 1, 3);
        long after = between(end, 5, 6);
        assertThat(after)
                .as("the rest of the 3 s after %d ms before the pause", before)
                .isGreaterThanOrEqualTo(3000 - before - 100)
                .isLessThan(3000);
    }

    @Test
    void aRunWhoseTimeIsUpStopsWithASavepointAndGoesOnFromIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        dispatcher = withCheckpoints(dir);
        String probe = Probe.create();
        definitions.put(
                "slow",
                () -> TestConnectors.job(
                        probe,
                        "rows: 2000, splits: 4",
                        null,
                        "commit: transactional, slow-ms: 1",
                        1,
                        "timeout: { run: 500ms, action: savepoint }"));
        dispatcher.submit(submission("run-1", "slow", null));
        Dispatcher.RunStatus end = awaitEnd("run-1");
        assertThat(end.state()).isEqualTo(State.TIMEOUT);
        assertThat(end.transitions())
                .extracting(RunStates.Transition::to)
                .containsExactly(State.QUEUE, State.RUNNING, State.CANCELLING, State.TIMEOUT);
        assertThat(end.transitions().get(2).reason()).isEqualTo("timeout of PT0.5S, with a savepoint");
        String reason = end.transitions().get(3).reason();
        assertThat(reason).startsWith("timed out; savepoint run-1/savepoints/");
        assertThat(Probe.get(probe).rows).as("the run stopped early").hasSizeLessThan(2000);

        // the next attempt goes on from the savepoint, with the time that is left
        dispatcher.submit(new Dispatcher.Submission(
                "job-1",
                "run-1",
                "slow",
                Map.of(),
                null,
                Map.of(),
                Duration.ofMinutes(1),
                new org.devlive.pipeforge.engine.master.checkpoint.RestorePoint.Settings(
                        reason.substring("timed out; savepoint ".length()), false, false),
                2));
        Dispatcher.RunStatus next = awaitEnd("run-1");
        assertThat(next.state()).isEqualTo(State.SUCCESS);
        assertThat(next.attempt()).isEqualTo(2);
        assertThat(Probe.get(probe).rows.stream().map(r -> (Long) r.get(0)).toList())
                .hasSize(2000)
                .doesNotHaveDuplicates();
    }

    @Test
    void theQueueTimeoutOfAJobReplacesTheClusterDefault() {
        String probe = Probe.create();
        assertThat(Placement.queueTimeout(
                        TestConnectors.job(probe, "rows: 1", null, "", 1, "timeout: { queue: 10s }"),
                        Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(Placement.queueTimeout(TestConnectors.job(probe, "rows: 1", null, "", 1), Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofMinutes(5));
    }
}
