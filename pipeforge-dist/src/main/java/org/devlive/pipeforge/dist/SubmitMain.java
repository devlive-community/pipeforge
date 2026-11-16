/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.dist.config.Configuration;
import org.devlive.pipeforge.dist.config.Settings;
import org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.grpc.RpcErrors;
import org.devlive.pipeforge.engine.rpc.proto.DispatcherServiceGrpc;
import org.devlive.pipeforge.engine.rpc.proto.JobRequest;
import org.devlive.pipeforge.engine.rpc.proto.JobStatus;
import org.devlive.pipeforge.engine.rpc.proto.RunState;
import org.devlive.pipeforge.engine.rpc.proto.SubmitJobRequest;

/**
 * Submits a job file to a master and waits for its end: the client of the development environment
 * until the server and {@code pipeforge run} submit jobs to a cluster.
 *
 * <pre>
 * SubmitMain &lt;job.yaml&gt; [--master host:port] [--timeout 5min]
 * </pre>
 *
 * <p>The cluster token and the master come from the settings of {@code pipeforge.home}, its
 * environment variables, or {@code --master}. The exit code is 0 when the run succeeds, 1 when it
 * does not, 2 for wrong arguments.
 */
public final class SubmitMain {

    private SubmitMain() {}

    public static void main(String[] args) {
        int code = run(args, System.getenv(), ClusterMain.home(), System.out, System.err);
        System.exit(code);
    }

    static int run(String[] args, Map<String, String> environment, Path home, PrintStream out, PrintStream err) {
        Path file = null;
        Map<String, String> overrides = new LinkedHashMap<>();
        Duration timeout = Duration.ofMinutes(5);
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--master" -> overrides.put(Settings.WORKER_MASTER.key(), value(args, ++i));
                    case "--timeout" -> timeout = Configuration.duration(value(args, ++i));
                    default -> {
                        if (file != null || args[i].startsWith("-")) {
                            throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
                        }
                        file = Path.of(args[i]);
                    }
                }
            }
            if (file == null) {
                throw new IllegalArgumentException("usage: <job.yaml> [--master host:port] [--timeout 5min]");
            }
        } catch (IllegalArgumentException e) {
            err.println("ERROR " + e.getMessage());
            return 2;
        }
        String definition;
        Configuration config;
        try {
            definition = Files.readString(file, StandardCharsets.UTF_8);
            config = Configuration.load(home.resolve("conf"), environment, overrides);
        } catch (IOException e) {
            err.println("ERROR cannot read " + file + ": " + e.getMessage());
            return 2;
        } catch (PipeForgeException e) {
            err.println("ERROR " + e.getMessage());
            return 2;
        }
        String master = config.string(Settings.WORKER_MASTER);
        String run = UUID.randomUUID().toString();
        ManagedChannel channel;
        try {
            channel = GrpcTransport.channel(master, Security.of(config, home));
        } catch (IOException e) {
            err.println("ERROR cannot reach " + master + ": " + e.getMessage());
            return 1;
        }
        try {
            var dispatcher = DispatcherServiceGrpc.newBlockingStub(channel);
            dispatcher.submitJob(SubmitJobRequest.newBuilder()
                    .setHeader(header())
                    .setJobId(String.valueOf(file.getFileName()))
                    .setRunId(run)
                    .setDefinition(definition)
                    .setDefinitionFormat("yaml")
                    .build());
            out.println("Submitted run " + run + " to " + master + ".");
            long deadline = System.nanoTime() + timeout.toNanos();
            RunState shown = RunState.RUN_STATE_UNSPECIFIED;
            while (true) {
                JobStatus status = dispatcher.getJobStatus(JobRequest.newBuilder()
                        .setHeader(header())
                        .setRunId(run)
                        .build());
                if (status.getState() != shown) {
                    shown = status.getState();
                    out.println("  " + name(shown));
                }
                if (status.getEndMs() != 0) {
                    var metrics = dispatcher.getJobMetrics(JobRequest.newBuilder()
                            .setHeader(header())
                            .setRunId(run)
                            .build());
                    metrics.getNodesList()
                            .forEach(n -> out.println(
                                    "  " + n.getNodeId() + ": " + n.getRowsIn() + " in, " + n.getRowsOut() + " out"));
                    if (shown == RunState.RUN_STATE_SUCCESS) {
                        return 0;
                    }
                    if (status.hasError()) {
                        err.println("ERROR " + status.getError().getCode() + " "
                                + status.getError().getMessage());
                    }
                    return 1;
                }
                if (System.nanoTime() > deadline) {
                    err.println("ERROR run " + run + " did not end within " + timeout);
                    return 1;
                }
                Thread.sleep(250);
            }
        } catch (StatusRuntimeException e) {
            err.println("ERROR " + RpcErrors.fromStatus(e, master).getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } finally {
            channel.shutdownNow();
        }
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("'" + args[i - 1] + "' needs a value");
        }
        return args[i];
    }

    static String name(RunState state) {
        return state.name().substring("RUN_STATE_".length());
    }

    private static org.devlive.pipeforge.engine.rpc.proto.Header header() {
        return Headers.create(Headers.Epochs.FIRST, Duration.ofSeconds(30), System::currentTimeMillis);
    }
}
