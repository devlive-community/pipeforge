/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.TaskState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The workers of a cluster as the master knows them: who registered, when each last sent a
 * heartbeat, which slots it has and what runs in them. A worker that sends no heartbeat for the
 * heartbeat timeout is removed, and the listeners learn about it with {@link
 * ErrorCode#WORKER_LOST}.
 *
 * <p>The state changes only through the methods of this class, one at a time; the gRPC service and
 * the job masters call it from their own threads.
 */
public final class ResourceManager {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceManager.class);

    /**
     * How workers prove that they live.
     *
     * @param heartbeatInterval how often a worker sends a heartbeat
     * @param heartbeatTimeout after how long without one a worker counts as lost
     * @param leaderEpoch the epoch of this master; 1 while there is one master
     */
    public record Settings(Duration heartbeatInterval, Duration heartbeatTimeout, long leaderEpoch) {
        public static final Settings DEFAULTS = new Settings(Duration.ofSeconds(5), Duration.ofSeconds(30), 1);

        public Settings {
            if (heartbeatInterval.isNegative()
                    || heartbeatInterval.isZero()
                    || heartbeatTimeout.compareTo(heartbeatInterval) <= 0) {
                throw new IllegalArgumentException(
                        "The heartbeat timeout must be longer than the positive heartbeat interval");
            }
        }
    }

    /**
     * What a worker tells about itself when it registers.
     *
     * @param rpcAddress where the master calls the worker, {@code host:port}
     * @param dataAddress where other workers fetch its data, {@code host:port}
     * @param slots how many tasks it runs at the same time
     * @param publicKey the X25519 key that sensitive options are sealed for
     * @param previousWorkerId the ID it had before, when it registers again
     */
    @SuppressWarnings("ArrayRecordComponent") // copied in and out, never shared
    public record WorkerSpec(
            String engineVersion,
            String rpcAddress,
            String dataAddress,
            Resources resources,
            int slots,
            Map<String, String> labels,
            String resourceGroup,
            byte[] publicKey,
            @Nullable String previousWorkerId) {

        public WorkerSpec {
            Objects.requireNonNull(rpcAddress, "rpcAddress");
            if (slots < 1) {
                throw new IllegalArgumentException("A worker offers at least one slot, not " + slots);
            }
            labels = Map.copyOf(labels);
            publicKey = publicKey.clone();
        }

        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }
    }

    /** The resources of a worker: cores and the memory for tasks, the network and managed state. */
    public record Resources(int cpuCores, long taskHeapBytes, long networkBytes, long managedBytes) {}

    /** One slot as the worker reports it. */
    public record Slot(int index, boolean allocated, @Nullable String jobId, @Nullable String allocationId) {
        public static Slot free(int index) {
            return new Slot(index, false, null, null);
        }
    }

    /** A task a worker runs. */
    public record RunningTask(ExecutionAttemptId id, TaskState state) {}

    /** The answer to a registration. */
    public record Registration(
            String workerId, long leaderEpoch, Duration heartbeatInterval, Duration heartbeatTimeout) {}

    /** The answer to a heartbeat: {@code reregister} when this master does not know the worker. */
    public record HeartbeatAck(long leaderEpoch, boolean reregister) {}

    /** A free slot of a worker. */
    public record FreeSlot(String workerId, String rpcAddress, int index) {}

    /** A registered worker, as seen at one moment. */
    public record Worker(
            String id, WorkerSpec spec, List<Slot> slots, List<RunningTask> tasks, Duration sinceHeartbeat) {

        public long freeSlots() {
            return slots.stream().filter(s -> !s.allocated()).count();
        }
    }

    /** A worker that was removed because it sent no heartbeat. */
    public record WorkerLost(String workerId, String rpcAddress, List<RunningTask> tasks, PipeForgeException error) {}

    /** Learns about lost workers, e.g. a job master whose tasks ran there. */
    @FunctionalInterface
    public interface Listener {
        void workerLost(WorkerLost lost);
    }

    private static final class Entry {
        final String id;
        final WorkerSpec spec;
        List<Slot> slots;
        List<RunningTask> tasks = List.of();
        long lastHeartbeat;

        Entry(String id, WorkerSpec spec, long now) {
            this.id = id;
            this.spec = spec;
            this.lastHeartbeat = now;
            List<Slot> free = new ArrayList<>();
            for (int i = 0; i < spec.slots(); i++) {
                free.add(Slot.free(i));
            }
            this.slots = List.copyOf(free);
        }
    }

    private final Settings settings;
    private final LongSupplier nanoTime;
    private final Map<String, Entry> workers = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public ResourceManager(Settings settings) {
        this(settings, System::nanoTime);
    }

    ResourceManager(Settings settings, LongSupplier nanoTime) {
        this.settings = settings;
        this.nanoTime = nanoTime;
    }

    public Settings settings() {
        return settings;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * Registers a worker, or registers it again: a worker that comes back with its previous ID
     * keeps it, and its slots start free.
     */
    public synchronized Registration register(WorkerSpec spec) {
        String id = spec.previousWorkerId() != null && !spec.previousWorkerId().isBlank()
                ? spec.previousWorkerId()
                : UUID.randomUUID().toString();
        boolean again = workers.containsKey(id);
        workers.put(id, new Entry(id, spec, nanoTime.getAsLong()));
        LOG.info(
                "Worker {} at {} {} with {} slot(s), labels {}",
                id,
                spec.rpcAddress(),
                again ? "registered again" : "registered",
                spec.slots(),
                spec.labels());
        return new Registration(id, settings.leaderEpoch(), settings.heartbeatInterval(), settings.heartbeatTimeout());
    }

    /** Records a heartbeat with the state of the worker's slots and tasks. */
    public synchronized HeartbeatAck heartbeat(String workerId, List<Slot> slots, List<RunningTask> tasks) {
        Entry entry = workers.get(workerId);
        if (entry == null) {
            return new HeartbeatAck(settings.leaderEpoch(), true);
        }
        entry.lastHeartbeat = nanoTime.getAsLong();
        if (!slots.isEmpty()) {
            entry.slots = merge(entry, slots);
        }
        entry.tasks = List.copyOf(tasks);
        return new HeartbeatAck(settings.leaderEpoch(), false);
    }

    /**
     * Records the slots a worker offers; returns {@code false} when the worker is not registered,
     * which then registers again after its next heartbeat.
     */
    public synchronized boolean offerSlots(String workerId, List<Slot> slots) {
        Entry entry = workers.get(workerId);
        if (entry == null) {
            return false;
        }
        entry.slots = merge(entry, slots);
        return true;
    }

    /** The reported slots, limited to the slots the worker registered with. */
    private static List<Slot> merge(Entry entry, List<Slot> reported) {
        Map<Integer, Slot> byIndex = new LinkedHashMap<>();
        entry.slots.forEach(s -> byIndex.put(s.index(), s));
        for (Slot slot : reported) {
            if (slot.index() >= 0 && slot.index() < entry.spec.slots()) {
                byIndex.put(slot.index(), slot);
            }
        }
        return List.copyOf(byIndex.values());
    }

    /**
     * Removes the workers whose last heartbeat is older than the timeout and tells the listeners;
     * returns what was removed.
     */
    public List<WorkerLost> expire() {
        List<WorkerLost> lost = new ArrayList<>();
        synchronized (this) {
            long now = nanoTime.getAsLong();
            long timeout = settings.heartbeatTimeout().toNanos();
            var iterator = workers.values().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                long silent = now - entry.lastHeartbeat;
                if (silent >= timeout) {
                    iterator.remove();
                    lost.add(new WorkerLost(
                            entry.id,
                            entry.spec.rpcAddress(),
                            entry.tasks,
                            PipeForgeException.of(ErrorCode.WORKER_LOST)
                                    .param("worker", entry.id)
                                    .param("address", entry.spec.rpcAddress())
                                    .param("seconds", TimeUnit.NANOSECONDS.toSeconds(silent))
                                    .build()));
                }
            }
        }
        for (WorkerLost worker : lost) {
            LOG.warn("{}", worker.error().getMessage());
            for (Listener listener : listeners) {
                try {
                    listener.workerLost(worker);
                } catch (RuntimeException e) {
                    LOG.error("A listener failed on the loss of worker {}", worker.workerId(), e);
                }
            }
        }
        return lost;
    }

    /** Checks for lost workers once per heartbeat interval, on {@code scheduler}. */
    public ScheduledFuture<?> scheduleChecks(ScheduledExecutorService scheduler) {
        long interval = settings.heartbeatInterval().toMillis();
        return scheduler.scheduleAtFixedRate(this::expire, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** The registered workers, in the order they registered. */
    public synchronized List<Worker> workers() {
        long now = nanoTime.getAsLong();
        List<Worker> list = new ArrayList<>();
        for (Entry entry : workers.values()) {
            list.add(new Worker(
                    entry.id, entry.spec, entry.slots, entry.tasks, Duration.ofNanos(now - entry.lastHeartbeat)));
        }
        return list;
    }

    /**
     * The free slots of the workers that carry every label of {@code labels}, worker by worker in
     * the order they registered.
     */
    public synchronized List<FreeSlot> freeSlots(Map<String, String> labels) {
        List<FreeSlot> free = new ArrayList<>();
        for (Entry entry : workers.values()) {
            if (!entry.spec.labels().entrySet().containsAll(labels.entrySet())) {
                continue;
            }
            entry.slots.stream()
                    .filter(s -> !s.allocated())
                    .sorted(Comparator.comparingInt(Slot::index))
                    .forEach(s -> free.add(new FreeSlot(entry.id, entry.spec.rpcAddress(), s.index())));
        }
        return free;
    }
}
