/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.engine.api.Bottleneck;
import org.devlive.pipeforge.engine.api.RunAnalysis;
import org.devlive.pipeforge.engine.master.RunStates.State;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.grpc.RpcErrors;
import org.devlive.pipeforge.engine.rpc.proto.DispatcherServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.Header;
import org.devlive.pipeforge.engine.rpc.proto.JobRequest;
import org.devlive.pipeforge.engine.rpc.proto.ListJobsRequest;
import org.devlive.pipeforge.engine.rpc.proto.PluginRef;
import org.devlive.pipeforge.engine.rpc.proto.RunState;
import org.devlive.pipeforge.engine.rpc.proto.SubmitJobRequest;
import org.devlive.pipeforge.engine.rpc.proto.TaskState;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The server's view of the dispatcher over gRPC. */
class DispatcherServiceTest {

    final DispatcherTest runs = new DispatcherTest();
    final String name = "dispatcher-" + UUID.randomUUID();
    Server server;
    ManagedChannel channel;
    DispatcherServiceGrpc.DispatcherServiceBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        server = InProcessServerBuilder.forName(name)
                .addService(new DispatcherService(runs.dispatcher))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).build();
        stub = DispatcherServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() throws InterruptedException {
        channel.shutdownNow();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        runs.stop();
    }

    static Header header() {
        return Headers.create(Headers.Epochs.FIRST, Duration.ofSeconds(5), System::currentTimeMillis);
    }

    SubmitJobRequest submit(String run, String definition) {
        return SubmitJobRequest.newBuilder()
                .setHeader(header())
                .setJobId("job-1")
                .setRunId(run)
                .setDefinition(definition)
                .putVariables("day", "2026-11-13")
                .addPlugins(PluginRef.newBuilder().setId("test").setVersion("1.0"))
                .build();
    }

    JobRequest request(String run) {
        return JobRequest.newBuilder().setHeader(header()).setRunId(run).build();
    }

    @Test
    void submitsWatchesAndListsRuns() throws Exception {
        runs.ok();
        assertThat(stub.submitJob(submit("run-1", "ok")).getRunId()).isEqualTo("run-1");
        runs.awaitEnd("run-1");

        var status = stub.getJobStatus(request("run-1"));
        assertThat(status.getState()).isEqualTo(RunState.RUN_STATE_SUCCESS);
        assertThat(status.getJobName()).isEqualTo("test-job");
        assertThat(status.getEndMs()).isGreaterThanOrEqualTo(status.getStartMs());
        assertThat(status.getTasksList()).singleElement().satisfies(task -> {
            assertThat(task.getState()).isEqualTo(TaskState.TASK_STATE_FINISHED);
            assertThat(task.getWorkerId()).isEqualTo("local");
        });
        var metrics = stub.getJobMetrics(request("run-1"));
        assertThat(metrics.getNodesList()).extracting(n -> n.getNodeId()).containsExactly("src", "up", "out");
        assertThat(metrics.getNodes(2).getRowsOut()).isEqualTo(20);

        runs.dispatcher.submit(DispatcherTest.submission("run-2", "ok", null));
        runs.awaitEnd("run-2");
        var first = stub.listJobs(
                ListJobsRequest.newBuilder().setHeader(header()).setLimit(1).build());
        assertThat(first.getJobsList()).extracting(j -> j.getRunId()).containsExactly("run-2");
        var second = stub.listJobs(ListJobsRequest.newBuilder()
                .setHeader(header())
                .setLimit(1)
                .setPageToken(first.getNextPageToken())
                .build());
        assertThat(second.getJobsList()).extracting(j -> j.getRunId()).containsExactly("run-1");
        assertThat(second.getNextPageToken()).isEmpty();
        assertThat(stub.listJobs(ListJobsRequest.newBuilder()
                                .setHeader(header())
                                .addStates(RunState.RUN_STATE_RUNNING)
                                .build())
                        .getJobsList())
                .isEmpty();
    }

    @Test
    void analysesARunAndFindsASlowTarget() throws Exception {
        String probe = Probe.create();
        runs.definitions.put("slow", () -> TestConnectors.job(probe, "rows: 200, splits: 2", "", "slow-ms: 5", 1));
        stub.submitJob(submit("run-1", "slow"));
        runs.awaitEnd("run-1");

        var result = stub.analyze(request("run-1"));
        assertThat(result.getSchemaVersion()).isEqualTo("1");
        RunAnalysis analysis = RunAnalysis.fromJson(result.getJson());
        assertThat(analysis.job()).isEqualTo("test-job");
        assertThat(analysis.status()).isEqualTo(org.devlive.pipeforge.engine.api.JobStatus.SUCCESS);
        assertThat(analysis.windowFromMs()).as("an ended run: the whole run").isZero();
        assertThat(analysis.metrics().rowsWritten()).isEqualTo(200);
        assertThat(analysis.metrics().runningMs()).isPositive();
        assertThat(analysis.rates()).extracting(RunAnalysis.NodeRate::node).containsExactly("src", "up", "out");
        assertThat(analysis.bottleneck().kind()).as(analysis.text()).isEqualTo(Bottleneck.Kind.TARGET);
        assertThat(analysis.bottleneck().node()).isEqualTo("out");
        assertThat(analysis.text()).contains("Bottleneck  TARGET at out: out busy");

        String blocked = runs.blocking();
        stub.submitJob(submit("run-2", "block"));
        assertThat(Probe.get(blocked).blocking.await(10, TimeUnit.SECONDS)).isTrue();
        RunAnalysis running =
                RunAnalysis.fromJson(stub.analyze(request("run-2")).getJson());
        assertThat(running.status())
                .isIn(
                        org.devlive.pipeforge.engine.api.JobStatus.RUNNING,
                        org.devlive.pipeforge.engine.api.JobStatus.CREATED);
        stub.cancelJob(request("run-2"));
        runs.awaitEnd("run-2");
    }

    @Test
    void cancelsAndReportsTheErrorOfARun() throws Exception {
        String probe = runs.blocking();
        stub.submitJob(submit("run-1", "block"));
        assertThat(Probe.get(probe).blocking.await(10, TimeUnit.SECONDS)).isTrue();
        stub.cancelJob(request("run-1"));
        assertThat(runs.awaitEnd("run-1").state()).isEqualTo(State.CANCELLED);
        assertThat(stub.getJobStatus(request("run-1")).getState()).isEqualTo(RunState.RUN_STATE_CANCELLED);

        String failing = Probe.create();
        runs.definitions.put("fail", () -> TestConnectors.job(failing, "rows: 100, splits: 2", "fail-at: 5", "", 1));
        stub.submitJob(submit("run-2", "fail"));
        runs.awaitEnd("run-2");
        assertThat(stub.getJobStatus(request("run-2")).getError().getCode()).isEqualTo("PF-PLUG-0007");
    }

    @Test
    void refusesUnknownRunsDefinitionsAndVersions() {
        assertThatThrownBy(() -> stub.getJobStatus(request("nope")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    assertThat(RpcErrors.fromStatus(e, "master").code()).isEqualTo(ErrorCode.RUN_NOT_FOUND);
                });
        assertThatThrownBy(() -> stub.submitJob(submit("run-1", "missing")))
                .satisfies(e ->
                        assertThat(RpcErrors.fromStatus(e, "master").code()).isEqualTo(ErrorCode.JOB_INVALID));
        assertThatThrownBy(() -> stub.cancelJob(JobRequest.newBuilder()
                        .setHeader(header().toBuilder().setProtocolVersion("2.0"))
                        .setRunId("x")
                        .build()))
                .satisfies(e ->
                        assertThat(RpcErrors.fromStatus(e, "master").code()).isEqualTo(ErrorCode.RPC_VERSION_MISMATCH));
        assertThatThrownBy(() -> stub.analyze(request("x"))).isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
            assertThat(RpcErrors.fromStatus(e, "master").code()).isEqualTo(ErrorCode.RUN_NOT_FOUND);
        });
    }

    @Test
    void savepointsAndRestorePointsTravelOverTheProtocol() throws Exception {
        var savepoint = org.devlive.pipeforge.engine.rpc.proto.SavepointRequest.newBuilder()
                .setHeader(header())
                .setName("before-upgrade");
        assertThatThrownBy(
                        () -> stub.triggerSavepoint(savepoint.setRunId("nope").build()))
                .satisfies(e ->
                        assertThat(RpcErrors.fromStatus(e, "master").code()).isEqualTo(ErrorCode.RUN_NOT_FOUND));

        // this master has no checkpoint storage: a running run takes no savepoint
        String blocked = runs.blocking();
        stub.submitJob(submit("run-1", "block"));
        assertThat(Probe.get(blocked).blocking.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(
                        () -> stub.triggerSavepoint(savepoint.setRunId("run-1").build()))
                .satisfies(e -> {
                    var error = RpcErrors.fromStatus(e, "master");
                    assertThat(error.code()).isEqualTo(ErrorCode.CHECKPOINT_FAILED);
                    assertThat(error.getMessage()).contains("takes no checkpoints");
                });
        stub.cancelJob(request("run-1"));
        runs.awaitEnd("run-1");

        // nor can it start a run from a savepoint
        String probe = Probe.create();
        runs.definitions.put("ok", () -> TestConnectors.job(probe, "", "", "", 1));
        stub.submitJob(submit("run-2", "ok").toBuilder()
                .setRestore(org.devlive.pipeforge.engine.rpc.proto.RestoreSettings.newBuilder()
                        .setPath("job-1/savepoints/before-upgrade-1/"))
                .build());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (stub.getJobStatus(request("run-2")).getState() != RunState.RUN_STATE_FAILED) {
            assertThat(System.nanoTime()).as("the run fails").isLessThan(deadline);
            Thread.sleep(10);
        }
        assertThat(stub.getJobStatus(request("run-2")).getError().getCode()).isEqualTo("PF-CKPT-0004");
    }

    @Test
    void mapsEveryRunState() {
        for (State state : State.values()) {
            assertThat(DispatcherService.fromProto(DispatcherService.toProto(state)))
                    .isEqualTo(state);
        }
        assertThatThrownBy(() -> DispatcherService.fromProto(RunState.RUN_STATE_UNSPECIFIED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsTheSplitsOfARunAndReadsTaskLogsThroughTheMaster() throws Exception {
        runs.ok();
        stub.submitJob(submit("run-1", "ok"));
        runs.awaitEnd("run-1");

        var splits = stub.listSplits(request("run-1")).getSplitsList();
        assertThat(splits).extracting(s -> s.getSplitId()).isNotEmpty().doesNotHaveDuplicates();
        assertThat(splits).allSatisfy(split -> {
            assertThat(split.getNodeId()).isEqualTo("src");
            assertThat(split.getSubtask()).isZero();
            assertThat(split.getState())
                    .isEqualTo(org.devlive.pipeforge.engine.rpc.proto.SplitState.SPLIT_STATE_FINISHED);
        });

        // the log of the source task, on the worker it ran on
        var attempt = runs.dispatcher.status("run-1").tasks().get(0).attempt();
        java.nio.file.Path file = runs.logs.path("test-job", attempt);
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(
                file, "2026-11-17 08:00:00.000 INFO  reading split a\n2026-11-17 08:00:01.000 WARN  slow target\n");
        var chunks = stub.readTaskLog(org.devlive.pipeforge.engine.rpc.proto.ReadTaskLogRequest.newBuilder()
                .setHeader(header())
                .setRunId("run-1")
                .setTask(org.devlive.pipeforge.engine.rpc.ProtoMapping.toProto(attempt))
                .setContains("slow")
                .build());
        StringBuilder text = new StringBuilder();
        chunks.forEachRemaining(chunk -> text.append(chunk.getData().toStringUtf8()));
        assertThat(text.toString()).contains("slow target").doesNotContain("reading split");

        // a task the run does not have, and a run the master does not know
        assertThatThrownBy(() -> stub.readTaskLog(org.devlive.pipeforge.engine.rpc.proto.ReadTaskLogRequest.newBuilder()
                                .setHeader(header())
                                .setRunId("run-1")
                                .setTask(org.devlive.pipeforge.engine.rpc.ProtoMapping.toProto(
                                        new org.devlive.pipeforge.engine.rpc.ExecutionAttemptId(
                                                "run-1", "nothing", 0, 0)))
                                .build())
                        .hasNext())
                .satisfies(e ->
                        assertThat(RpcErrors.fromStatus(e, "master").code()).isEqualTo(ErrorCode.TASK_LOG_NOT_FOUND));
        assertThatThrownBy(() -> stub.listSplits(request("nothing")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void reportsTheMasterAndItsWorkers() throws Exception {
        ResourceManager manager = new ResourceManager(ResourceManager.Settings.DEFAULTS);
        String worker = manager.register(new ResourceManager.WorkerSpec(
                        "0.1.0",
                        "w1:6124",
                        "w1:6125",
                        new ResourceManager.Resources(4, 2L << 30, 1L << 28, 0),
                        2,
                        java.util.Map.of("zone", "a"),
                        "etl",
                        new byte[32],
                        null))
                .workerId();
        assertThat(manager.reserve(new ResourceManager.FreeSlot(worker, "w1:6124", "w1:6125", 1), "job-1", "alloc-1"))
                .isTrue();
        String cluster = "cluster-" + UUID.randomUUID();
        Server withWorkers = InProcessServerBuilder.forName(cluster)
                .addService(new DispatcherService(runs.dispatcher, manager::workers))
                .build()
                .start();
        ManagedChannel to = InProcessChannelBuilder.forName(cluster).build();
        try {
            var status = DispatcherServiceGrpc.newBlockingStub(to)
                    .getCluster(org.devlive.pipeforge.engine.rpc.proto.ClusterRequest.newBuilder()
                            .setHeader(header())
                            .build());
            assertThat(status.getProtocolVersion()).isEqualTo("1.0");
            assertThat(status.getEngineVersion()).isNotBlank();
            assertThat(status.getStartedMs()).isPositive();
            assertThat(status.getWorkersList()).singleElement().satisfies(info -> {
                assertThat(info.getWorkerId()).isEqualTo(worker);
                assertThat(info.getRpcAddress()).isEqualTo("w1:6124");
                assertThat(info.getLabelsMap()).containsEntry("zone", "a");
                assertThat(info.getResourceGroup()).isEqualTo("etl");
                assertThat(info.getResources().getCpuCores()).isEqualTo(4);
                assertThat(info.getSlotsList())
                        .extracting(slot -> slot.getAllocated())
                        .containsExactly(false, true);
                assertThat(info.getSlots(1).getJobId()).isEqualTo("job-1");
            });
            // a dispatcher in one process has no registered workers
            assertThat(stub.getCluster(org.devlive.pipeforge.engine.rpc.proto.ClusterRequest.newBuilder()
                                    .setHeader(header())
                                    .build())
                            .getWorkersList())
                    .isEmpty();
        } finally {
            to.shutdownNow();
            withWorkers.shutdownNow();
        }
    }
}
