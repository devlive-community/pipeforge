/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.devlive.pipeforge.core.plan.Component;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PlannedNode;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.master.Dispatcher;
import org.devlive.pipeforge.engine.master.JobMasters;
import org.devlive.pipeforge.engine.master.Placement;
import org.devlive.pipeforge.engine.master.ResourceManager;
import org.devlive.pipeforge.engine.master.ResourceManagerService;
import org.devlive.pipeforge.engine.master.Scheduler;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.network.NetworkOptions;
import org.devlive.pipeforge.engine.rpc.ChainCodec;
import org.devlive.pipeforge.engine.rpc.TaskExecutorGateway;
import org.devlive.pipeforge.engine.rpc.grpc.ClusterToken;
import org.devlive.pipeforge.engine.rpc.grpc.Fencing;
import org.devlive.pipeforge.engine.rpc.grpc.Headers;
import org.devlive.pipeforge.engine.rpc.grpc.IdempotencyCache;
import org.devlive.pipeforge.engine.rpc.grpc.JobMasterClient;
import org.devlive.pipeforge.engine.rpc.grpc.JobMasterService;
import org.devlive.pipeforge.engine.rpc.grpc.Retry;
import org.devlive.pipeforge.engine.rpc.grpc.SecretBox;
import org.devlive.pipeforge.engine.rpc.grpc.TaskExecutorClient;
import org.devlive.pipeforge.engine.rpc.grpc.TaskExecutorService;
import org.devlive.pipeforge.engine.worker.MasterConnection;
import org.devlive.pipeforge.engine.worker.SlotTable;
import org.devlive.pipeforge.engine.worker.TaskExecutor;

/**
 * A master and {@code n} workers in one process, connected the way a cluster is: the services of
 * the master and of every worker are served over in-process gRPC with a cluster token, the workers
 * register and send heartbeats, the scheduler reserves their slots and the tasks exchange rows
 * through the data exchange of their worker. Components are not loaded from plugin packages: the
 * workers take them from the plan of the run, which lives in the same process.
 */
final class LocalCluster implements AutoCloseable {

    /** Slots of a local worker: local runs are not limited by slots, only by the machine. */
    static final int SLOTS_PER_WORKER = 256;

    private static final AtomicInteger COUNT = new AtomicInteger();

    private final String name = "pipeforge-local-" + COUNT.incrementAndGet() + "-" + UUID.randomUUID();
    private final String secret = UUID.randomUUID().toString();
    private final ClusterToken token = new ClusterToken(secret);
    private final ScheduledExecutorService threads = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "local-cluster");
        thread.setDaemon(true);
        return thread;
    });
    private final Retry retry = new Retry(threads, Duration.ofMillis(50));
    private final ResourceManager manager = new ResourceManager(ResourceManager.Settings.DEFAULTS);
    private final JobMasters jobs = new JobMasters();
    private final Map<String, JobPlan> plans = new ConcurrentHashMap<>();
    private final Map<String, SubmitOptions> options = new ConcurrentHashMap<>();
    private final Map<String, TaskExecutorGateway> gateways = new ConcurrentHashMap<>();
    private final List<LocalWorker> workers = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();
    private final Server master;
    private final Scheduler scheduler;
    private final Dispatcher dispatcher;

    /** Starts the master and {@code count} workers and waits until every worker has registered. */
    LocalCluster(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("a local run needs at least one worker, not " + count);
        }
        manager.addListener(jobs);
        try {
            master = InProcessServerBuilder.forName(name + "-master")
                    .intercept(token.server())
                    .addService(new ResourceManagerService(manager))
                    .addService(new JobMasterService(jobs, new IdempotencyCache()))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        scheduler = new Scheduler(manager, this::gateway, threads, Duration.ofMillis(10));
        dispatcher = new Dispatcher(
                submission -> {
                    JobPlan plan = plans.get(submission.runId());
                    if (plan == null) {
                        throw new IllegalStateException("no plan for run " + submission.runId());
                    }
                    return new Dispatcher.Planned(plan, () -> plans.remove(submission.runId()), options(submission));
                },
                plan -> Placement.cluster(
                        scheduler, this::gateway, Map.of(), Scheduler.Strategy.SPREAD, Duration.ofMinutes(5)),
                jobs,
                SubmitOptions.defaults(),
                threads,
                Clock.systemUTC(),
                100);
        try {
            for (int i = 1; i <= count; i++) {
                workers.add(new LocalWorker("worker-" + i, count > 1));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (manager.workers().size() < count) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("the local workers did not register");
                }
                Thread.sleep(2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("interrupted while the local workers registered", e);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private SubmitOptions options(Dispatcher.Submission submission) {
        return options.getOrDefault(submission.runId(), SubmitOptions.defaults());
    }

    private TaskExecutorGateway gateway(ResourceManager.FreeSlot slot) {
        TaskExecutorGateway gateway = gateways.get(slot.rpcAddress());
        if (gateway == null) {
            throw new IllegalStateException("no local worker at " + slot.rpcAddress());
        }
        return gateway;
    }

    /** Starts a run of {@code plan}. */
    org.devlive.pipeforge.engine.api.JobHandle submit(String runId, JobPlan plan, SubmitOptions settings) {
        plans.put(runId, plan);
        options.put(runId, settings);
        try {
            dispatcher.submit(
                    new Dispatcher.Submission(plan.name(), runId, plan.name(), Map.of(), null, Map.of(), null));
        } catch (RuntimeException e) {
            plans.remove(runId);
            throw e;
        } finally {
            options.remove(runId);
        }
        return dispatcher.handle(runId);
    }

    JobMasters jobs() {
        return jobs;
    }

    int workers() {
        return manager.workers().size();
    }

    /** The data exchanges of the workers, for tests. */
    List<NetworkEnvironment> networks() {
        return workers.stream().map(w -> w.network).toList();
    }

    /** The component a task description names, from the plan of its run. */
    private Component component(
            String runId, org.devlive.pipeforge.core.job.NodeKind kind, String type, String plugin) {
        JobPlan plan = plans.get(runId);
        if (plan == null) {
            throw new IllegalStateException("run " + runId + " is not running here");
        }
        for (PlannedNode node : plan.nodes()) {
            if (node.kind() == kind && node.type().equals(type) && node.plugin().equals(plugin)) {
                return node.component();
            }
        }
        throw new IllegalStateException("the task description names " + kind.id() + " " + type + " of " + plugin
                + ", which run " + runId + " does not use");
    }

    private ManagedChannel channel(String target) {
        ManagedChannel channel = InProcessChannelBuilder.forName(target)
                .intercept(token.client())
                .build();
        synchronized (channels) {
            channels.add(channel);
        }
        return channel;
    }

    @Override
    public void close() {
        dispatcher.close();
        for (LocalWorker worker : workers) {
            worker.close();
        }
        synchronized (channels) {
            channels.forEach(ManagedChannel::shutdownNow);
        }
        if (master != null) {
            master.shutdownNow();
        }
        threads.shutdownNow();
    }

    /** One worker: its task executor, slots and data exchange, served like a worker process. */
    private final class LocalWorker {
        final NetworkEnvironment network;
        final TaskExecutor executor;
        final Server server;
        final MasterConnection connection;

        LocalWorker(String id, boolean overTcp) {
            String address = name + "-" + id;
            SecretBox keys = SecretBox.generate();
            SlotTable slots = new SlotTable(address, SLOTS_PER_WORKER);
            network = new NetworkEnvironment(id, secret, NetworkOptions.defaults());
            String data;
            if (overTcp) {
                InetSocketAddress bound = network.start("127.0.0.1", 0);
                data = "127.0.0.1:" + bound.getPort();
            } else {
                network.startLocal();
                data = "local";
            }
            ManagedChannel toMaster = channel(name + "-master");
            executor = new TaskExecutor(address, new JobMasterClient(toMaster, "the master", retry), slots, network);
            try {
                server = InProcessServerBuilder.forName(address)
                        .intercept(token.server())
                        .addService(new TaskExecutorService(
                                executor,
                                keys,
                                (run, plugins) -> (kind, type, plugin) -> component(run, kind, type, plugin),
                                new Fencing(),
                                new IdempotencyCache()))
                        .build()
                        .start();
            } catch (IOException e) {
                network.close();
                executor.close();
                throw new UncheckedIOException(e);
            }
            gateways.put(
                    address,
                    new TaskExecutorClient(
                            channel(address),
                            address,
                            keys.publicKey(),
                            () -> Headers.Epochs.FIRST,
                            retry,
                            ChainCodec::pluginRef));
            connection = new MasterConnection(
                    toMaster,
                    new MasterConnection.Config(
                            address, data, SLOTS_PER_WORKER, Map.of(), "default", Duration.ofSeconds(1)),
                    keys.publicKey(),
                    executor,
                    slots);
            connection.start(threads);
        }

        void close() {
            connection.close();
            executor.close();
            server.shutdownNow();
            network.close();
        }
    }
}
