/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.dist.config.Configuration;
import org.devlive.pipeforge.dist.config.Settings;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.master.Dispatcher;
import org.devlive.pipeforge.engine.master.DispatcherService;
import org.devlive.pipeforge.engine.master.JobMasters;
import org.devlive.pipeforge.engine.master.Placement;
import org.devlive.pipeforge.engine.master.PluginBlobs;
import org.devlive.pipeforge.engine.master.PluginPlanning;
import org.devlive.pipeforge.engine.master.ResourceManager;
import org.devlive.pipeforge.engine.master.ResourceManagerService;
import org.devlive.pipeforge.engine.master.Scheduler;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.grpc.BlobServer;
import org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.grpc.IdempotencyCache;
import org.devlive.pipeforge.engine.rpc.grpc.JobMasterService;
import org.devlive.pipeforge.engine.rpc.grpc.Retry;
import org.devlive.pipeforge.engine.rpc.grpc.TaskExecutorClient;
import org.devlive.pipeforge.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The master process: the resource manager the workers register with, the dispatcher that accepts
 * runs, the job master of every run, and the plugin packages the workers download. All of it is
 * served on {@code master.rpc.port}.
 */
public final class MasterProcess implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MasterProcess.class);

    private final GrpcTransport.Security security;
    private final ScheduledExecutorService threads = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "master");
        thread.setDaemon(true);
        return thread;
    });
    private final Retry retry = new Retry(threads, Duration.ofMillis(200));
    private final ResourceManager manager;
    private final PluginManager plugins;
    private final PluginBlobs blobs;
    private final Dispatcher dispatcher;
    private final Map<String, TaskExecutorClient> clients = new ConcurrentHashMap<>();
    private final List<ManagedChannel> channels = new ArrayList<>();
    private final Server server;

    public MasterProcess(Configuration config, Path home) {
        this.security = Security.of(config, home);
        Path dir = home.resolve(config.string(Settings.MASTER_DIR));
        List<Path> pluginDirs =
                config.list(Settings.PLUGIN_DIRS).stream().map(home::resolve).toList();
        this.plugins = PluginManager.load(pluginDirs, dir.resolve("plugins"), PluginManager.Options.defaults());
        this.blobs = new PluginBlobs(plugins, dir.resolve("blobs"));
        this.manager = new ResourceManager(new ResourceManager.Settings(
                config.duration(Settings.HEARTBEAT_INTERVAL),
                config.duration(Settings.HEARTBEAT_TIMEOUT),
                ResourceManager.Settings.DEFAULTS.leaderEpoch()));
        JobMasters jobs = new JobMasters();
        manager.addListener(jobs);
        Scheduler scheduler = new Scheduler(manager, this::gateway, threads, Duration.ofMillis(100));
        Scheduler.Strategy strategy = strategy(config.string(Settings.SCHEDULER_STRATEGY));
        Duration slotTimeout = config.duration(Settings.SLOT_REQUEST_TIMEOUT);
        this.dispatcher = new Dispatcher(
                new PluginPlanning(plugins),
                plan -> Placement.cluster(scheduler, this::gateway, Map.of(), strategy, slotTimeout),
                jobs,
                SubmitOptions.defaults().withCancelTimeout(config.duration(Settings.CANCEL_TIMEOUT)),
                threads,
                Clock.systemUTC(),
                1000);
        try {
            server = GrpcTransport.serve(
                    config.integer(Settings.MASTER_RPC_PORT),
                    security,
                    List.of(
                            new ResourceManagerService(manager),
                            new JobMasterService(jobs, new IdempotencyCache()),
                            new DispatcherService(dispatcher),
                            new BlobServer(blobs)));
        } catch (IOException e) {
            close();
            throw PipeForgeException.of(ErrorCode.PORT_IN_USE)
                    .param("port", config.integer(Settings.MASTER_RPC_PORT))
                    .cause(e)
                    .build();
        }
        LOG.info(
                "Master of cluster {} listens on port {} with {} plugin(s)",
                config.string(Settings.CLUSTER_NAME),
                server.getPort(),
                plugins.plugins().size());
    }

    static Scheduler.Strategy strategy(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "spread" -> Scheduler.Strategy.SPREAD;
            case "pack" -> Scheduler.Strategy.PACK;
            default -> throw PipeForgeException.of(ErrorCode.SETTING_INVALID)
                    .param("key", Settings.SCHEDULER_STRATEGY.key())
                    .param("source", "the configuration")
                    .param("reason", "'" + value + "' is not spread or pack")
                    .build();
        };
    }

    /** The task executor of the worker that has {@code slot}, over the RPC. */
    private TaskExecutorGateway gateway(ResourceManager.FreeSlot slot) {
        return clients.computeIfAbsent(slot.workerId(), id -> {
            ResourceManager.Worker worker = manager.workers().stream()
                    .filter(w -> w.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("worker " + id + " is not registered"));
            ManagedChannel channel;
            try {
                channel = GrpcTransport.channel(worker.spec().rpcAddress(), security);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            synchronized (channels) {
                channels.add(channel);
            }
            return new TaskExecutorClient(
                    channel,
                    worker.spec().rpcAddress(),
                    worker.spec().publicKey(),
                    () -> Headers.Epochs.FIRST,
                    retry,
                    blobs::ref);
        });
    }

    /** The port the master listens on. */
    public int port() {
        return server.getPort();
    }

    public ResourceManager resourceManager() {
        return manager;
    }

    public Dispatcher dispatcher() {
        return dispatcher;
    }

    @Override
    public void close() {
        if (dispatcher != null) {
            dispatcher.close();
        }
        if (server != null) {
            server.shutdownNow();
        }
        synchronized (channels) {
            channels.forEach(ManagedChannel::shutdownNow);
        }
        if (plugins != null) {
            plugins.close();
        }
        threads.shutdownNow();
    }
}
