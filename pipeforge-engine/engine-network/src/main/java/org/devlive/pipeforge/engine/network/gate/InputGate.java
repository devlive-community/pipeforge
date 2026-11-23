/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.gate;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.NetworkEvent;
import org.devlive.pipeforge.engine.network.NetworkOptions;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.devlive.pipeforge.engine.network.buffer.LocalBufferPool;
import org.devlive.pipeforge.engine.network.buffer.NetworkBufferPool;
import org.devlive.pipeforge.engine.network.partition.ResultPartitionManager;
import org.jspecify.annotations.Nullable;

/**
 * An input of a task: one channel per upstream subpartition, read by the task thread in the order
 * buffers arrive. The end of every channel ends the input. The gate reserves the buffers of its
 * remote channels and its floating buffers when it is created, so a running input never waits for
 * memory.
 */
public final class InputGate implements AutoCloseable {

    /** Where a channel reads from: the producer's worker, or {@code null} for this worker. */
    public record ChannelSpec(ResultPartitionId partition, int subpartition, @Nullable InetSocketAddress producer) {}

    /** A buffer of the input and the channel it came from. */
    public record Next(int channel, Buffer buffer) {}

    /** What the input measured. */
    public record Metrics(long buffers, long bytes, long waitMs, double bufferUsage, List<Integer> credits) {}

    private final String owner;
    private final int bufferSize;
    private final List<InputChannel> channels = new ArrayList<>();
    private final ArrayDeque<InputChannel> available = new ArrayDeque<>();
    private final @Nullable LocalBufferPool pool;
    private final int floatingTotal;
    private int floatingFree;
    private int ended;
    private @Nullable PipeForgeException failure;
    private boolean closed;
    private long buffers;
    private long bytes;
    private long waitNanos;

    /**
     * @param owner how errors name the input, e.g. {@code run-1/sink/0 input 0}
     */
    public InputGate(
            String owner,
            List<ChannelSpec> specs,
            NetworkOptions options,
            NetworkBufferPool network,
            ResultPartitionManager partitions,
            RemoteInputChannel.Connector connector,
            ScheduledExecutorService timer) {
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("an input needs at least one channel");
        }
        this.owner = owner;
        this.bufferSize = options.bufferSize();
        this.floatingTotal = options.floatingBuffersPerGate();
        this.floatingFree = floatingTotal;
        long remote = specs.stream().filter(s -> s.producer() != null).count();
        int memory = remote == 0 ? 0 : (int) remote * options.buffersPerChannel() + floatingTotal;
        this.pool = memory == 0 ? null : network.createLocalPool(owner, memory, memory);
        for (ChannelSpec spec : specs) {
            int index = channels.size();
            InetSocketAddress producer = spec.producer();
            channels.add(
                    producer == null
                            ? new LocalInputChannel(
                                    this,
                                    index,
                                    spec.partition(),
                                    spec.subpartition(),
                                    options.buffersPerChannel(),
                                    partitions,
                                    timer)
                            : new RemoteInputChannel(
                                    this,
                                    index,
                                    spec.partition(),
                                    spec.subpartition(),
                                    options.buffersPerChannel(),
                                    producer,
                                    connector));
        }
    }

    /** Asks every upstream subpartition for its data. */
    public void start() {
        channels.forEach(InputChannel::request);
    }

    public String owner() {
        return owner;
    }

    public int channels() {
        return channels.size();
    }

    public InputChannel channel(int index) {
        return channels.get(index);
    }

    /**
     * The next buffer: rows ({@link Buffer.Kind#DATA}, to be decoded with the row codec) or an
     * event other than the end of a channel. The caller recycles it.
     *
     * @return {@code null} when nothing arrived within {@code timeout} or the input has ended; see
     *     {@link #isFinished()}
     * @throws PipeForgeException when a channel failed
     */
    public @Nullable Next next(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            InputChannel channel;
            synchronized (this) {
                while (true) {
                    if (failure != null) {
                        throw failure;
                    }
                    if (ended == channels.size() || closed) {
                        return null;
                    }
                    channel = available.poll();
                    if (channel != null && channel.blocked) {
                        // read again once the checkpoint aligned; its buffers hold back its credit
                        channel.queued = false;
                        channel.parked = true;
                        continue;
                    }
                    if (channel != null) {
                        channel.queued = false;
                        break;
                    }
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return null;
                    }
                    long started = System.nanoTime();
                    wait(Math.max(1, left / 1_000_000), (int) (left % 1_000_000));
                    waitNanos += System.nanoTime() - started;
                }
            }
            Buffer buffer = channel.poll();
            if (buffer == null) {
                // the channel tells the gate again when it has a buffer or gets credit back
                continue;
            }
            if (channel.hasMore()) {
                available(channel);
            }
            if (buffer.isEvent() && NetworkEvent.fromBuffer(buffer) instanceof NetworkEvent.EndOfPartition) {
                buffer.recycle();
                synchronized (this) {
                    channel.ended = true;
                    ended++;
                }
                channel.returnAllFloating();
                channel.release();
                continue;
            }
            if (!buffer.isEvent()) {
                // rows only, as the output counts them
                synchronized (this) {
                    buffers++;
                    bytes += buffer.size();
                }
            }
            return new Next(channel.index, buffer);
        }
    }

    /**
     * Stops reading a channel until {@link #unblock}: its buffers stay in it, so it gives no credit
     * back and its producer stops once the credit it has is used. Events need no credit, so the
     * barriers of the other channels still arrive.
     */
    public synchronized void block(int index) {
        channels.get(index).blocked = true;
    }

    /** Reads a channel again; buffers that arrived meanwhile come first. */
    public void unblock(int index) {
        InputChannel channel = channels.get(index);
        boolean parked;
        synchronized (this) {
            channel.blocked = false;
            parked = channel.parked;
            channel.parked = false;
        }
        if (parked) {
            available(channel);
        }
    }

    /** Whether a channel is blocked. */
    public synchronized boolean isBlocked(int index) {
        return channels.get(index).blocked;
    }

    /** Whether a channel has ended: its producer finished it. */
    public synchronized boolean isEnded(int index) {
        return channels.get(index).ended;
    }

    /** Whether every channel has ended. */
    public synchronized boolean isFinished() {
        return ended == channels.size();
    }

    synchronized boolean isEnded(InputChannel channel) {
        return channel.ended;
    }

    /** A channel may have a buffer; from any thread. */
    void available(InputChannel channel) {
        synchronized (this) {
            if (!channel.queued && !channel.ended && !closed) {
                channel.queued = true;
                available.add(channel);
                notifyAll();
            }
        }
    }

    /** Fails the input; the task sees the error at its next read. */
    public void fail(PipeForgeException error) {
        synchronized (this) {
            if (failure == null && !closed && ended < channels.size()) {
                failure = error;
            }
            notifyAll();
        }
    }

    public synchronized @Nullable PipeForgeException failure() {
        return failure;
    }

    int bufferSize() {
        return bufferSize;
    }

    /** A buffer for a remote channel; there is one for every credit it announced. */
    @Nullable
    Buffer memory() {
        LocalBufferPool local = pool;
        return local == null ? null : local.poll();
    }

    synchronized int borrowFloating(int wanted) {
        int granted = Math.min(wanted, floatingFree);
        floatingFree -= granted;
        return granted;
    }

    synchronized void returnFloating() {
        floatingFree++;
    }

    /** Bytes of the network buffers the input holds now; 0 for an input of local channels only. */
    public long bytesInUse() {
        LocalBufferPool local = pool;
        return local == null ? 0 : (long) local.buffersInUse() * bufferSize;
    }

    public Metrics metrics() {
        List<Integer> credits = channels.stream().map(InputChannel::credit).toList();
        LocalBufferPool local = pool;
        synchronized (this) {
            return new Metrics(buffers, bytes, waitNanos / 1_000_000, local == null ? 0 : local.usage(), credits);
        }
    }

    /** Stops every channel and gives the memory back. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            available.clear();
            notifyAll();
        }
        channels.forEach(InputChannel::release);
        LocalBufferPool local = pool;
        if (local != null) {
            local.destroy();
        }
    }
}
