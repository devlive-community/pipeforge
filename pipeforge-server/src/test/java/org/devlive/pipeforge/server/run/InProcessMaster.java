/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.master.Dispatcher;
import org.devlive.pipeforge.engine.master.DispatcherService;
import org.devlive.pipeforge.engine.master.JobMasters;
import org.devlive.pipeforge.engine.master.Placement;
import org.devlive.pipeforge.engine.master.PluginPlanning;
import org.devlive.pipeforge.engine.rpc.InProcessRpc;
import org.devlive.pipeforge.engine.rpc.JobMasterGateway;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.worker.TaskExecutor;
import org.devlive.pipeforge.plugin.PluginManager;

/**
 * A master and a worker in this process: the dispatcher plans with the plugins it is given, and
 * serves the server over in-process gRPC, as a real master does over the network.
 */
final class InProcessMaster implements AutoCloseable {

    private final String name = "master-" + UUID.randomUUID();
    private final JobMasters jobs = new JobMasters();
    private final InProcessRpc.Endpoint<JobMasterGateway> master;
    private final TaskExecutor executor;
    private final InProcessRpc.Endpoint<TaskExecutorGateway> worker;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private Dispatcher dispatcher;
    private Server server;
    private final PluginManager plugins;
    /** The workers of the cluster; tests register their own. */
    final org.devlive.pipeforge.engine.master.ResourceManager manager =
            new org.devlive.pipeforge.engine.master.ResourceManager(
                    org.devlive.pipeforge.engine.master.ResourceManager.Settings.DEFAULTS);
    /** The task logs of the worker. */
    final org.devlive.pipeforge.engine.worker.log.LogService logs;

    InProcessMaster(PluginManager plugins) {
        this.plugins = plugins;
        master = InProcessRpc.serve(name + "-jobs", jobs);
        try {
            logs = new org.devlive.pipeforge.engine.worker.log.LogService(
                    "local",
                    org.devlive.pipeforge.engine.worker.log.LogService.Settings.defaults(
                            java.nio.file.Files.createTempDirectory("in-process-logs")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        executor = new TaskExecutor("local", master.gateway(), null, null, logs);
        worker = InProcessRpc.serve(name + "-worker", executor);
        start();
    }

    private void start() {
        dispatcher = new Dispatcher(
                new PluginPlanning(plugins),
                plan -> Placement.local(worker.gateway()),
                jobs,
                SubmitOptions.defaults(),
                timers,
                Clock.systemUTC(),
                100);
        try {
            server = InProcessServerBuilder.forName(name)
                    .addService(new DispatcherService(dispatcher, manager::workers))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Forgets every run, as a master that restarted: the server's runs are lost. */
    void restart() {
        server.shutdownNow();
        dispatcher.close();
        start();
    }

    MasterClient client() {
        return new GrpcMasterClient(InProcessChannelBuilder.forName(name).build(), "in-process");
    }

    @Override
    public void close() {
        server.shutdownNow();
        dispatcher.close();
        executor.close();
        logs.close();
        worker.close();
        master.close();
        timers.shutdownNow();
    }
}
