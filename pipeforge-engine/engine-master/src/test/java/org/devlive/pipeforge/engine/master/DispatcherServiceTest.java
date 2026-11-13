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
        assertThatThrownBy(() -> stub.analyze(request("x")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED));
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
}
