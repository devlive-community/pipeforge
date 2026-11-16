/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.gate;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.UUID;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.jspecify.annotations.Nullable;

/**
 * A channel to a subpartition on another worker. It asks for the subpartition with its initial
 * credit, copies every data buffer that arrives into a buffer of the gate, and announces new
 * credit when the task has read a buffer or the producer reports a backlog.
 */
public final class RemoteInputChannel extends InputChannel {

    /** The connection a remote channel talks over. */
    public interface Connection {
        void requestPartition(RemoteInputChannel channel);

        void addCredit(RemoteInputChannel channel, int credit);

        void cancel(RemoteInputChannel channel);

        void unregister(RemoteInputChannel channel);
    }

    /** Opens connections to other workers. */
    @FunctionalInterface
    public interface Connector {
        java.util.concurrent.CompletableFuture<? extends Connection> connect(InetSocketAddress address);
    }

    private final UUID id = UUID.randomUUID();
    private final InetSocketAddress producer;
    private final Connector connector;
    private final ArrayDeque<Buffer> received = new ArrayDeque<>();
    private volatile @Nullable Connection connection;
    private volatile boolean released;
    private long expected;

    RemoteInputChannel(
            InputGate gate,
            int index,
            ResultPartitionId partition,
            int subpartition,
            int exclusive,
            InetSocketAddress producer,
            Connector connector) {
        super(gate, index, partition, subpartition, exclusive);
        this.producer = producer;
        this.connector = connector;
    }

    public UUID id() {
        return id;
    }

    public InetSocketAddress producer() {
        return producer;
    }

    /** The credit announced in the partition request. */
    public int requestCredit() {
        return initialCredit();
    }

    // the callback handles the outcome; the future itself has nothing more to report
    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    void request() {
        connector.connect(producer).whenComplete((created, error) -> {
            if (error != null) {
                gate.fail(error instanceof PipeForgeException e ? e : PipeForgeException.wrap(error));
                return;
            }
            connection = created;
            if (!released) {
                created.requestPartition(this);
            }
        });
    }

    /**
     * A buffer arrived from the producer; called by the connection.
     *
     * @param data the content, copied before this method returns
     */
    public void onBuffer(long sequence, int backlog, Buffer.Kind kind, java.nio.ByteBuffer data) {
        if (released) {
            return;
        }
        if (sequence != expected) {
            fail(PipeForgeException.of(ErrorCode.INTERNAL)
                    .param("detail", "buffer " + sequence + " of channel " + id + " where " + expected + " was due")
                    .build());
            return;
        }
        expected++;
        Buffer buffer;
        if (kind == Buffer.Kind.EVENT) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            buffer = Buffer.unpooled(Buffer.Kind.EVENT, bytes);
        } else {
            if (credit() <= 0) {
                fail(PipeForgeException.of(ErrorCode.INTERNAL)
                        .param("detail", "a data buffer without credit on channel " + id)
                        .build());
                return;
            }
            Buffer memory = data.remaining() > gate.bufferSize() ? null : gate.memory();
            if (memory == null) {
                // a row larger than a buffer: its memory is not from the pool
                memory = new Buffer(new byte[data.remaining()], m -> {});
            }
            int size = data.remaining();
            data.get(memory.memory(), 0, size);
            memory.setSize(size);
            took();
            Buffer owned = memory;
            buffer = owned.onRecycle(() -> {
                int credit = recycled();
                Connection current = connection;
                if (credit > 0 && current != null && !released) {
                    current.addCredit(this, credit);
                }
            });
        }
        synchronized (received) {
            received.add(buffer);
        }
        int credit = backlog(backlog);
        Connection current = connection;
        if (credit > 0 && current != null) {
            current.addCredit(this, credit);
        }
        gate.available(this);
    }

    /** The producer or the connection failed this channel. */
    public void fail(PipeForgeException error) {
        if (!released) {
            gate.fail(error);
        }
    }

    /** Whether the subpartition ended; a lost connection does not fail a channel that ended. */
    public boolean isEnded() {
        return gate.isEnded(this);
    }

    @Override
    @Nullable
    Buffer poll() {
        synchronized (received) {
            return received.poll();
        }
    }

    @Override
    boolean hasMore() {
        synchronized (received) {
            return !received.isEmpty();
        }
    }

    @Override
    void release() {
        boolean wasReleased = released;
        released = true;
        Connection current = connection;
        if (!wasReleased && current != null) {
            if (!isEnded()) {
                current.cancel(this);
            }
            current.unregister(this);
        }
        synchronized (received) {
            received.forEach(Buffer::recycle);
            received.clear();
        }
        returnAllFloating();
    }
}
