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
import org.devlive.pipeforge.engine.rpc.grpc.LeaderGate;
import org.devlive.pipeforge.engine.rpc.grpc.Retry;
import org.devlive.pipeforge.engine.rpc.grpc.TaskExecutorClient;
import org.devlive.pipeforge.engine.rpc.proto.BlobServiceGrpc;
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
    private final org.devlive.pipeforge.engine.api.checkpoint.@org.jspecify.annotations.Nullable CheckpointStorage
            checkpoints;
    private final com.zaxxer.hikari.@org.jspecify.annotations.Nullable HikariDataSource metastore;
    /** Elects the leader among the masters of the cluster; {@code null} without a metastore: one master. */
    private volatile org.devlive.pipeforge.engine.master.@org.jspecify.annotations.Nullable LeaderElection election;

    private final List<Runnable> lost = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closing;
    /** Deletes old checkpoints, so that slow storage does not hold up a job master. */
    private final java.util.concurrent.ExecutorService checkpointIo = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "checkpoint-io");
        thread.setDaemon(true);
        return thread;
    });

    public MasterProcess(Configuration config, Path home) {
        this.security = Security.of(config, home);
        // checked first: a process that cannot keep checkpoints does not start
        this.checkpoints = CheckpointSetup.open(config, "master");
        this.metastore = openMetastore(config, home);
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
        // without these checks a worker that dies is never removed, and its runs never fail
        manager.scheduleChecks(threads);
        Scheduler scheduler = new Scheduler(manager, this::gateway, threads, Duration.ofMillis(100));
        Scheduler.Strategy strategy = strategy(config.string(Settings.SCHEDULER_STRATEGY));
        Duration slotTimeout = config.duration(Settings.SLOT_REQUEST_TIMEOUT);
        this.dispatcher = new Dispatcher(
                new PluginPlanning(plugins),
                plan -> Placement.cluster(
                        scheduler, this::gateway, Map.of(), strategy, Placement.queueTimeout(plan, slotTimeout)),
                jobs,
                SubmitOptions.defaults().withCancelTimeout(config.duration(Settings.CANCEL_TIMEOUT)),
                threads,
                Clock.systemUTC(),
                1000);
        if (checkpoints != null && metastore != null) {
            var store = new org.devlive.pipeforge.engine.master.checkpoint.JdbcCheckpointStore(
                    metastore, MasterMetastore.safeUrl(config.string(Settings.MASTER_METASTORE_URL)));
            try {
                store.check();
            } catch (RuntimeException e) {
                close();
                throw e;
            }
            dispatcher.withCheckpoints(
                    new org.devlive.pipeforge.engine.master.checkpoint.Checkpoints(checkpoints, store, checkpointIo));
        }
        String host = config.optional(Settings.MASTER_HOST).orElseGet(WorkerProcess::hostName);
        boolean elects = metastore != null;
        try {
            server = GrpcTransport.serve(
                    config.integer(Settings.MASTER_RPC_PORT),
                    security,
                    List.of(
                            new ResourceManagerService(manager),
                            new JobMasterService(jobs, new IdempotencyCache()),
                            new DispatcherService(dispatcher, manager::workers, control()),
                            new BlobServer(blobs)),
                    List.of(new LeaderGate(
                            () -> !elects || isLeader(),
                            this::leaderAddress,
                            () -> host + ":" + port(),
                            java.util.Set.of(BlobServiceGrpc.SERVICE_NAME))));
        } catch (IOException e) {
            close();
            throw PipeForgeException.of(ErrorCode.PORT_IN_USE)
                    .param("port", config.integer(Settings.MASTER_RPC_PORT))
                    .cause(e)
                    .build();
        }
        if (metastore != null) {
            elect(config, host, metastore);
        }
        LOG.info(
                "Master of cluster {} listens on port {} with {} plugin(s)",
                config.string(Settings.CLUSTER_NAME),
                server.getPort(),
                plugins.plugins().size());
    }

    /**
     * Competes for the leader lease of the cluster: this master serves only while it holds it. The
     * first look happens now, so a master alone in its cluster leads once it started.
     */
    private void elect(Configuration config, String host, javax.sql.DataSource source) {
        var elected = new org.devlive.pipeforge.engine.master.LeaderElection(
                source,
                MasterMetastore.safeUrl(config.string(Settings.MASTER_METASTORE_URL)),
                new org.devlive.pipeforge.engine.master.LeaderElection.Settings(
                        config.string(Settings.CLUSTER_NAME),
                        java.util.UUID.randomUUID().toString(),
                        host + ":" + server.getPort(),
                        config.duration(Settings.LEADER_LEASE)),
                System::nanoTime,
                Clock.systemUTC(),
                new org.devlive.pipeforge.engine.master.LeaderElection.Listener() {
                    @Override
                    public void granted(long epoch) {
                        manager.leaderEpoch(epoch);
                    }

                    @Override
                    public void revoked() {
                        if (!closing) {
                            LOG.error("Master {} is no longer the leader of its cluster and stops", host);
                            lost.forEach(Runnable::run);
                        }
                    }
                });
        try {
            elected.check();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
        election = elected;
        elected.start(threads);
        if (!elected.isLeader()) {
            LOG.info(
                    "Master {} is a standby; the leader is '{}'",
                    host + ":" + server.getPort(),
                    elected.leaderAddress());
        }
    }

    /** What {@code pipeforge cluster drain}, {@code uncordon} and {@code failover} change. */
    private DispatcherService.Control control() {
        return new DispatcherService.Control() {
            @Override
            public void drain(String workerId) {
                manager.drain(workerId);
            }

            @Override
            public void uncordon(String workerId) {
                manager.uncordon(workerId);
            }

            @Override
            public void releaseLeadership() {
                handOver();
            }
        };
    }

    /**
     * Gives the lease up so that a standby takes over at its next look, then stops as a master that
     * lost the lead does: the call is answered first.
     *
     * @throws PipeForgeException {@code PF-CLUS-0019} for a master that elects no leader
     */
    @SuppressWarnings("FutureReturnValueIgnored") // the election reports the end of the lead
    void handOver() {
        var current = election;
        if (current == null) {
            throw PipeForgeException.of(ErrorCode.FAILOVER_UNAVAILABLE)
                    .param("master", leaderAddress().isEmpty() ? "localhost:" + port() : leaderAddress())
                    .build();
        }
        LOG.warn("Master {} hands the lead of its cluster over, as asked", current.leaderAddress());
        threads.schedule(current::close, 200, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Whether this master leads its cluster: always without a metastore, where it is the only one. */
    public boolean isLeader() {
        var current = election;
        return metastore == null || (current != null && current.isLeader());
    }

    /** The epoch requests to workers carry: one more with every new leader. */
    public long leaderEpoch() {
        return manager.leaderEpoch();
    }

    /** Where the leader is, as this master knows; empty when it does not. */
    String leaderAddress() {
        var current = election;
        return current == null ? "" : current.leaderAddress();
    }

    /**
     * Runs {@code listener} when this master loses the lead it had; the process should then stop, as
     * {@code ClusterMain} does, so that it starts again as a standby.
     */
    public void onLeadershipLost(Runnable listener) {
        lost.add(listener);
    }

    /**
     * The metastore the checkpoints are recorded in and the masters elect their leader in; {@code
     * checkpoint.dir} needs it. {@code null} without {@code master.metastore.url}.
     */
    private com.zaxxer.hikari.@org.jspecify.annotations.Nullable HikariDataSource openMetastore(
            Configuration config, Path home) {
        try {
            var opened = MasterMetastore.open(config, home);
            if (opened == null && checkpoints != null) {
                throw PipeForgeException.of(ErrorCode.SETTING_INVALID)
                        .param("key", Settings.MASTER_METASTORE_URL.key())
                        .param("source", "the configuration")
                        .param("reason", "checkpoint.dir is set, and the master records checkpoints in the metastore")
                        .build();
            }
            return opened;
        } catch (RuntimeException e) {
            CheckpointSetup.close(checkpoints);
            throw e;
        }
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
                    () -> new Headers.Epochs(manager.leaderEpoch(), 1),
                    retry,
                    blobs::refs);
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
        closing = true;
        var current = election;
        if (current != null) {
            // gives the lease up first, so that a standby takes over at once
            current.close();
        }
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
        CheckpointSetup.close(checkpoints);
        checkpointIo.shutdown();
        if (metastore != null) {
            metastore.close();
        }
        threads.shutdownNow();
    }

    /** The checkpoint storage of {@code checkpoint.dir}, or empty without it. */
    public java.util.Optional<org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage> checkpointStorage() {
        return java.util.Optional.ofNullable(checkpoints);
    }
}
