/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.api.factory.EngineKind;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalEngineTest {

    private LocalEngine engine;

    @BeforeEach
    void start() {
        engine = new LocalEngine();
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private JobResult run(JobPlan plan, SubmitOptions options) throws Exception {
        return engine.submit(plan, options).result().get(30, TimeUnit.SECONDS);
    }

    private JobResult run(JobPlan plan) throws Exception {
        return run(plan, SubmitOptions.defaults());
    }

    @Test
    void describesTheEngine() {
        assertThat(engine.kind()).isEqualTo(EngineKind.NATIVE);
        assertThat(engine.capabilities().batch()).isTrue();
        assertThat(engine.capabilities().streaming()).isTrue();
        assertThat(engine.capabilities().exactlyOnce()).isFalse();
        assertThat(engine.capabilities().savepoints()).isFalse();
        assertThat(engine.capabilities().regionRecovery()).isFalse();
    }

    @Test
    void runsAJobFromTheSourceToTheSink() throws Exception {
        String probe = Probe.create();
        JobHandle handle = engine.submit(TestConnectors.job(probe, "rows: 100, splits: 7", "", "", 1));
        JobResult result = handle.result().get(30, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(result.error()).isNull();
        assertThat(result.id()).isEqualTo(handle.id());
        assertThat(handle.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(handle.name()).isEqualTo("test-job");
        assertThat(handle.metrics().elapsedMs()).isEqualTo(result.metrics().elapsedMs());
        Probe seen = Probe.get(probe);
        assertThat(seen.rows).hasSize(100);
        assertThat(seen.rows.stream().map(r -> (Long) r.get(0)).distinct()).hasSize(100);
        assertThat(seen.enumeratorThreads).containsExactly("jobmaster-test-job");
        assertThat(seen.readerThreads).containsExactly("task-src-0");
        assertThat(seen.events).contains("reader 0 registered true", "enumerator closed");
    }

    @Test
    void runsOneTaskPerParallelInstanceAndSharesTheSplits() throws Exception {
        String probe = Probe.create();
        JobResult result = run(TestConnectors.job(probe, "rows: 1000, splits: 20", "", "", 3));

        assertThat(result.status()).isEqualTo(JobStatus.SUCCESS);
        Probe seen = Probe.get(probe);
        assertThat(seen.rows).hasSize(1000);
        assertThat(seen.readerThreads).containsExactlyInAnyOrder("task-src-0", "task-src-1", "task-src-2");
        assertThat(seen.writerThreads).containsExactlyInAnyOrder("writer-src-0", "writer-src-1", "writer-src-2");
        assertThat(seen.events).contains("transform opened 2/3 1");
    }

    @Test
    void commitsWhatTheSinkPreparedOnceEveryTaskFinished() throws Exception {
        String probe = Probe.create();
        JobResult result = run(TestConnectors.job(probe, "rows: 10, splits: 10", null, "commit: global", 2));

        assertThat(result.status()).isEqualTo(JobStatus.SUCCESS);
        Probe seen = Probe.get(probe);
        assertThat(seen.committed).hasSize(2).allSatisfy(c -> assertThat(c).endsWith("@1"));
        assertThat(seen.globallyCommitted).singleElement().satisfies(g -> assertThat(g)
                .contains("+"));
    }

    @Test
    void failsWhenTheCommitDoesNotSucceed() throws Exception {
        String probe = Probe.create();
        JobResult retried = run(TestConnectors.job(probe, "", null, "commit: retry-forever", 1));

        assertThat(retried.status()).isEqualTo(JobStatus.FAILED);
        assertThat(retried.error().code().id()).isEqualTo("PF-SINK-0003");
        assertThat((String) retried.error().params().get("reason")).contains("after 3 attempts");
        assertThat(Probe.get(probe).commitAttempts).isEqualTo(3);

        JobResult failed = run(TestConnectors.job(Probe.create(), "", null, "commit: fail", 1));
        assertThat(failed.error().code().id()).isEqualTo("PF-SINK-0003");
        assertThat((String) failed.error().params().get("reason")).isEqualTo("commit: IOException: target unavailable");
        assertThat(failed.error().scope().node()).isEqualTo("out");
    }

    @Test
    void failsTheJobWithTheFirstTaskErrorAndCancelsTheOthers() throws Exception {
        String probe = Probe.create();
        JobResult result = run(TestConnectors.job(probe, "rows: 100000, splits: 4", "fail-at: 50", "", 2));

        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        PipeForgeException error = result.error();
        assertThat(error.code().id()).isEqualTo("PF-PLUG-0007");
        assertThat(error.scope().node()).isEqualTo("up");
        assertThat(error.scope().run()).isEqualTo(result.id().value());
        assertThat(Probe.get(probe).rows).hasSizeLessThan(100000);
    }

    @Test
    void failsTheJobWhenTheEnumeratorFails() throws Exception {
        JobResult result = run(TestConnectors.job(Probe.create(), "fail-enumerator: true", null, "", 1));

        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error().code().id()).isEqualTo("PF-SRC-0001");
        assertThat((String) result.error().params().get("reason"))
                .isEqualTo("start the enumerator: IllegalStateException: cannot list the splits");
        assertThat(result.error().scope().node()).isEqualTo("src");
    }

    @Test
    void cancelsARunningJob() throws Exception {
        String probe = Probe.create();
        JobHandle handle = engine.submit(TestConnectors.job(probe, "block: cancel", null, "", 2));
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(handle.status()).isEqualTo(JobStatus.RUNNING);
        handle.cancel();
        handle.cancel();

        JobResult result = handle.result().get(30, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(result.error()).isNull();
        assertThat(Probe.get(probe).committed).isEmpty();
    }

    @Test
    void failsACancelledJobWhoseTaskCannotBeStopped() throws Exception {
        String probe = Probe.create();
        JobHandle handle = engine.submit(
                TestConnectors.job(probe, "block: forever", null, "", 1),
                SubmitOptions.defaults().withCancelTimeout(Duration.ofMillis(50)));
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
        handle.cancel();

        JobResult result = handle.result().get(30, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error().code().id()).isEqualTo("PF-RES-0005");
        Probe.get(probe).release.countDown();
    }

    @Test
    void writesOnTheTaskThreadWhenTheBufferIsOff() throws Exception {
        String probe = Probe.create();
        JobResult result = run(
                TestConnectors.job(probe, "", "", "", 1),
                SubmitOptions.defaults()
                        .withAsyncWriteBuffer(false)
                        .withWriteBatchSize(2)
                        .withWriteQueueCapacity(1));

        assertThat(result.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(Probe.get(probe).writerThreads).containsExactly("task-src-0");
    }

    @Test
    void closingTheEngineCancelsItsJobs() throws Exception {
        String probe = Probe.create();
        JobHandle handle = engine.submit(TestConnectors.job(probe, "block: cancel", null, "", 1));
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
        engine.close();

        assertThat(handle.result().get(1, TimeUnit.SECONDS).status()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void rejectsJobsThatNeedDataExchange() {
        JobPlan twoSources = TestConnectors.plan(
                """
                version: 1
                name: merge
                source:
                  - { id: a, type: numbers, probe: x }
                  - { id: b, type: numbers, probe: x }
                sink:
                  - { id: out, type: collect, probe: x, inputs: [a, b] }
                """);
        assertThatThrownBy(() -> engine.submit(twoSources)).isInstanceOfSatisfying(PipeForgeException.class, e -> {
            assertThat(e.code().id()).isEqualTo("PF-PLAN-0012");
            assertThat(e.scope().node()).isEqualTo("b");
        });

        JobPlan fanOut = TestConnectors.plan(
                """
                version: 1
                name: fan-out
                source:
                  - { id: a, type: numbers, probe: x }
                sink:
                  - { id: one, type: collect, probe: x, inputs: [a] }
                  - { id: two, type: collect, probe: x, inputs: [a] }
                """);
        assertThatThrownBy(() -> engine.submit(fanOut))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> assertThat(e.getMessage())
                        .contains("its output goes to 2 nodes"));

        JobPlan rescale = TestConnectors.plan(
                """
                version: 1
                name: rescale
                env: { parallelism: 2 }
                source:
                  - { id: a, type: numbers, probe: x }
                sink:
                  - { id: out, type: collect, probe: x, parallelism: 1 }
                """);
        assertThatThrownBy(() -> engine.submit(rescale)).isInstanceOfSatisfying(PipeForgeException.class, e -> {
            assertThat(e.scope().node()).isEqualTo("out");
            assertThat(e.getMessage()).contains("parallelism 1 differs from the source's 2");
        });
    }

    @Test
    void explainsThePlan() {
        JobPlan plan = TestConnectors.job(Probe.create(), "", "", "", 1);

        assertThat(engine.explain(plan).text()).contains("Job test-job");
        assertThat(engine.explain(plan).plan().nodes()).hasSize(3);
    }
}
