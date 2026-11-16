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
    final TaskExecutor executor = new TaskExecutor("local", master.gateway());
    final InProcessRpc.Endpoint<TaskExecutorGateway> worker = InProcessRpc.serve("worker", executor);
    final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    final Map<String, Supplier<JobPlan>> definitions = new ConcurrentHashMap<>();
    final AtomicInteger planned = new AtomicInteger();
    final List<String> released = new CopyOnWriteArrayList<>();
    Dispatcher dispatcher = dispatcher(100);

    Dispatcher dispatcher(int retained) {
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
                SubmitOptions.defaults(),
                timers,
                Clock.systemUTC(),
                retained);
    }

    @AfterEach
    void stop() {
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
}
