/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist;

import io.grpc.Server;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.ComponentProvider;
import org.devlive.pipeforge.dist.config.Configuration;
import org.devlive.pipeforge.dist.config.Settings;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.network.NetworkOptions;
import org.devlive.pipeforge.engine.rpc.grpc.BlobClient;
import org.devlive.pipeforge.engine.rpc.grpc.Fencing;
import org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport;
import org.devlive.pipeforge.engine.rpc.grpc.IdempotencyCache;
import org.devlive.pipeforge.engine.rpc.grpc.JobMasterClient;
import org.devlive.pipeforge.engine.rpc.grpc.MasterChannel;
import org.devlive.pipeforge.engine.rpc.grpc.Retry;
import org.devlive.pipeforge.engine.rpc.grpc.SecretBox;
import org.devlive.pipeforge.engine.rpc.grpc.TaskExecutorService;
import org.devlive.pipeforge.engine.worker.ClassLoaderLeaks;
import org.devlive.pipeforge.engine.worker.HeapGuard;
import org.devlive.pipeforge.engine.worker.MasterConnection;
import org.devlive.pipeforge.engine.worker.MemoryLimits;
import org.devlive.pipeforge.engine.worker.PluginCache;
import org.devlive.pipeforge.engine.worker.SlotTable;
import org.devlive.pipeforge.engine.worker.TaskExecutor;
import org.devlive.pipeforge.engine.worker.Workspace;
import org.devlive.pipeforge.engine.worker.log.LogService;
import org.devlive.pipeforge.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A worker process: it registers with the master, offers its slots, runs the tasks the master
 * deploys with the plugins it downloads from the master, and exchanges rows with other workers on
 * {@code worker.data.port}.
 */
public final class WorkerProcess implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerProcess.class);

    private final ScheduledExecutorService threads = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "worker");
        thread.setDaemon(true);
        return thread;
    });
    private final NetworkEnvironment network;
    private final MasterChannel toMaster;
    private final TaskExecutor executor;
    private final LogService logs;
    private final PluginCache plugins;
    private final Server server;
    private final org.devlive.pipeforge.engine.api.checkpoint.@org.jspecify.annotations.Nullable CheckpointStorage
            checkpoints;
    private final MasterConnection connection;

    public WorkerProcess(Configuration config, Path home) {
        GrpcTransport.Security security = Security.of(config, home);
        String host = config.optional(Settings.WORKER_HOST).orElseGet(WorkerProcess::hostName);
        int rpcPort = config.integer(Settings.WORKER_RPC_PORT);
        String name = host + ":" + rpcPort;
        // checked first: a process that cannot keep checkpoints does not start
        this.checkpoints = CheckpointSetup.open(config, "worker-" + name);
        NetworkOptions exchange = networkOptions(config);
        network = new NetworkEnvironment(name, config.string(Settings.CLUSTER_TOKEN), exchange);
        InetSocketAddress data;
        try {
            data = network.start("0.0.0.0", config.integer(Settings.WORKER_DATA_PORT));
        } catch (RuntimeException e) {
            network.close();
            throw e;
        }
        int slots = config.integer(Settings.WORKER_SLOTS, Runtime.getRuntime().availableProcessors());
        SlotTable table = new SlotTable(name, slots);
        toMaster = new MasterChannel(
                MasterChannel.addresses(config.string(Settings.WORKER_MASTER)),
                address -> GrpcTransport.channel(address, security));
        try {
            // opens the first one now: a trust store that cannot be read stops the start
            toMaster.authority();
        } catch (java.io.UncheckedIOException e) {
            network.close();
            throw new IllegalStateException(
                    "cannot reach the master: " + e.getCause().getMessage(), e);
        }
        Retry retry = new Retry(threads, Duration.ofMillis(200));
        logs = new LogService(
                name,
                new LogService.Settings(
                        home.resolve(config.string(Settings.WORKER_LOG_DIR)),
                        config.size(Settings.WORKER_LOG_MAX_FILE_SIZE),
                        config.integer(Settings.WORKER_LOG_MAX_FILES),
                        config.duration(Settings.WORKER_LOG_RETENTION)));
        threads.scheduleWithFixedDelay(this::cleanLogs, 1, 60, TimeUnit.MINUTES);
        TaskExecutor.Options options = TaskExecutor.Options.of(memoryLimits(config, exchange, slots))
                .withCancelKillTimeout(config.duration(Settings.CANCEL_KILL_TIMEOUT))
                .withHeapGuard(heapGuard(config))
                .withWorkspace(new Workspace.Settings(
                        home.resolve(config.string(Settings.WORKER_DIR)).resolve("jobs"),
                        config.size(Settings.DISK_QUOTA_PER_JOB)));
        if (checkpoints != null) {
            options = options.withCheckpointStorage(checkpoints);
        }
        executor = new TaskExecutor(
                name, new JobMasterClient(toMaster, "the master", retry), table, network, logs, options);
        plugins = new PluginCache(
                new BlobClient(toMaster)::fetch,
                home.resolve(config.string(Settings.WORKER_DIR)).resolve("plugins"),
                noBuiltins(),
                PluginManager.Options.defaults(),
                new ClassLoaderLeaks(config.duration(Settings.CLASS_LOADER_LEAK_TIMEOUT)));
        executor.onRunEnded(plugins::release);
        threads.scheduleWithFixedDelay(this::checkClassLoaders, 10, 10, TimeUnit.SECONDS);
        SecretBox keys = SecretBox.generate();
        Fencing fencing = new Fencing();
        try {
            server = GrpcTransport.serve(
                    rpcPort,
                    security,
                    List.of(new TaskExecutorService(executor, keys, plugins::forRun, fencing, new IdempotencyCache())));
        } catch (IOException e) {
            executor.close();
            network.close();
            toMaster.close();
            throw PipeForgeException.of(ErrorCode.PORT_IN_USE)
                    .param("port", rpcPort)
                    .cause(e)
                    .build();
        }
        connection = new MasterConnection(
                toMaster,
                new MasterConnection.Config(
                        name,
                        host + ":" + data.getPort(),
                        slots,
                        config.map(Settings.WORKER_LABELS),
                        config.string(Settings.WORKER_RESOURCE_GROUP),
                        config.duration(Settings.WORKER_RETRY)),
                keys.publicKey(),
                executor,
                table);
        // requests of the leaders before the one this worker registered with are refused
        connection.onLeaderEpoch(fencing::learnLeader);
        connection.start(threads);
        LOG.info(
                "Worker {} offers {} slot(s), exchanges data on port {} and registers with {}",
                name,
                slots,
                data.getPort(),
                config.string(Settings.WORKER_MASTER));
    }

    /** The data exchange settings, with the network memory derived from the heap when it is not set. */
    static NetworkOptions networkOptions(Configuration config) {
        long derived =
                Math.max(64L << 20, Math.min(1L << 30, Runtime.getRuntime().maxMemory() / 10));
        NetworkOptions defaults = NetworkOptions.defaults();
        return new NetworkOptions(
                config.size(Settings.NETWORK_MEMORY, derived),
                (int) config.size(Settings.BUFFER_SIZE),
                config.integer(Settings.BUFFERS_PER_CHANNEL),
                config.integer(Settings.FLOATING_BUFFERS),
                (int) Math.min(Integer.MAX_VALUE, config.size(Settings.MAX_ROW_BYTES)),
                config.duration(Settings.BUFFER_TIMEOUT),
                defaults.pingInterval(),
                defaults.partitionRequestTimeout(),
                defaults.connectTimeout());
    }

    /** The memory limits of the tasks, with the task memory derived from the heap when it is not set. */
    static MemoryLimits memoryLimits(Configuration config, NetworkOptions exchange, int slots) {
        long left = Math.max(0, Runtime.getRuntime().maxMemory() - exchange.memoryBytes());
        long derived = Math.max(16L << 20, left / 2 / Math.max(1, slots));
        return new MemoryLimits(config.size(Settings.MAX_ROW_BYTES), config.size(Settings.TASK_MEMORY, derived));
    }

    /** When the heap guard slows down and fails the run that holds the most memory. */
    static HeapGuard.Settings heapGuard(Configuration config) {
        try {
            return new HeapGuard.Settings(
                    config.bool(Settings.HEAP_GUARD_ENABLED),
                    config.integer(Settings.HEAP_GUARD_PAUSE_PERCENT),
                    config.integer(Settings.HEAP_GUARD_FAIL_PERCENT),
                    config.duration(Settings.HEAP_GUARD_FAIL_AFTER));
        } catch (IllegalArgumentException e) {
            throw PipeForgeException.of(ErrorCode.SETTING_INVALID)
                    .param(
                            "key",
                            Settings.HEAP_GUARD_PAUSE_PERCENT.key() + ", " + Settings.HEAP_GUARD_FAIL_PERCENT.key())
                    .param("source", "conf/worker.yaml")
                    .param("reason", e.getMessage())
                    .build();
        }
    }

    static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    /** Components come from plugin packages only; a worker has no builtin ones. */
    private static ComponentProvider noBuiltins() {
        return (kind, type, plugin) -> {
            throw PipeForgeException.of(ErrorCode.PLUGIN_NOT_FOUND)
                    .param("plugin", type)
                    .param("type", kind.id())
                    .build();
        };
    }

    private void checkClassLoaders() {
        try {
            plugins.leaks().check();
        } catch (RuntimeException e) {
            LOG.warn("Looking for leaked plugin class loaders failed", e);
        }
    }

    private void cleanLogs() {
        try {
            int removed = logs.cleanup(Instant.now());
            if (removed > 0) {
                LOG.info(
                        "Removed the task logs of {} runs older than {}",
                        removed,
                        logs.settings().retention());
            }
        } catch (RuntimeException e) {
            LOG.warn("Removing old task logs failed", e);
        }
    }

    /** The state of the connection to the master. */
    public MasterConnection.State state() {
        return connection.state();
    }

    @Override
    public void close() {
        connection.close();
        executor.close();
        logs.close();
        server.shutdownNow();
        plugins.close();
        network.close();
        toMaster.close();
        CheckpointSetup.close(checkpoints);
        threads.shutdownNow();
    }

    /** The checkpoint storage of {@code checkpoint.dir}, or empty without it. */
    public java.util.Optional<org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage> checkpointStorage() {
        return java.util.Optional.ofNullable(checkpoints);
    }
}
