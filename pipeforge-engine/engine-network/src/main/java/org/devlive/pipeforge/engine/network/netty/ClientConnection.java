/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.common.error.RemoteCause;
import org.devlive.pipeforge.engine.network.gate.RemoteInputChannel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The consumer end of the connection to another worker: the remote channels of all inputs on this
 * worker that read from there share it.
 */
// Netty reports a failed write through the pipeline (exceptionCaught, the close future), not
// through the future of the write.
@SuppressWarnings("FutureReturnValueIgnored")
final class ClientConnection extends SimpleChannelInboundHandler<Frame> implements RemoteInputChannel.Connection {

    private static final Logger LOG = LoggerFactory.getLogger(ClientConnection.class);

    private final InetSocketAddress address;
    private final String workerId;
    private final String token;
    private final Duration partitionRequestTimeout;
    private final CompletableFuture<ClientConnection> ready = new CompletableFuture<>();
    private final Map<UUID, RemoteInputChannel> channels = new ConcurrentHashMap<>();
    private volatile @Nullable Channel channel;
    private volatile boolean closing;
    private volatile String peer;

    ClientConnection(InetSocketAddress address, String workerId, String token, Duration partitionRequestTimeout) {
        this.address = address;
        this.workerId = workerId;
        this.token = token;
        this.partitionRequestTimeout = partitionRequestTimeout;
        this.peer = address.toString();
    }

    /** Completes once the other worker answered the Hello. */
    CompletableFuture<ClientConnection> ready() {
        return ready;
    }

    int channels() {
        return channels.size();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        ctx.writeAndFlush(new Frame.Hello(Frame.PROTOCOL_VERSION, workerId, token));
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        switch (frame) {
            case Frame.Hello hello -> {
                PipeForgeException refused = Handshake.check(hello, token, peer);
                if (refused != null) {
                    ready.completeExceptionally(refused);
                    Handshake.refuse(ctx, refused);
                    return;
                }
                peer = hello.workerId() + " (" + address + ")";
                ready.complete(this);
            }
            case Frame.BufferResponse response -> {
                try {
                    RemoteInputChannel target = channels.get(response.channel());
                    if (target != null) {
                        target.onBuffer(
                                response.sequence(),
                                response.backlog(),
                                response.kind(),
                                response.data().nioBuffer());
                    }
                } finally {
                    response.data().release();
                }
            }
            case Frame.ErrorResponse error -> {
                UUID id = error.channel();
                if (id != null) {
                    RemoteInputChannel target = channels.remove(id);
                    if (target != null) {
                        target.fail(remoteError(error, target));
                    }
                    return;
                }
                PipeForgeException failure = remoteError(error, null);
                ready.completeExceptionally(failure);
                failAll(failure);
                ctx.close();
            }
            case Frame.Ping ping -> ctx.writeAndFlush(new Frame.Pong());
            case Frame.Pong pong -> {}
            case Frame.CloseRequest close -> {
                closing = true;
                ctx.close();
            }
            case Frame.PartitionRequest request -> refuseFromProducer(ctx, frame);
            case Frame.AddCredit credit -> refuseFromProducer(ctx, frame);
            case Frame.CancelPartition cancel -> refuseFromProducer(ctx, frame);
        }
    }

    private void refuseFromProducer(ChannelHandlerContext ctx, Frame frame) {
        Handshake.refuse(
                ctx,
                FrameCodec.malformed("a " + frame.getClass().getSimpleName() + " sent to the consumer")
                        .error());
    }

    /** The error the producer sent, as an error of this worker. */
    private PipeForgeException remoteError(Frame.ErrorResponse error, @Nullable RemoteInputChannel target) {
        RemoteCause cause = new RemoteCause(error.code() + " " + error.message(), null);
        if (error.code().equals(ErrorCode.PARTITION_NOT_FOUND.id()) && target != null) {
            return PipeForgeException.of(ErrorCode.PARTITION_NOT_FOUND)
                    .param("partition", target.partition().toString())
                    .param("worker", peer)
                    .param("seconds", Math.max(1, partitionRequestTimeout.toSeconds()))
                    .cause(cause)
                    .build();
        }
        if (error.code().equals(ErrorCode.RPC_UNAUTHENTICATED.id())) {
            return PipeForgeException.of(ErrorCode.RPC_UNAUTHENTICATED)
                    .param("peer", peer)
                    .cause(cause)
                    .build();
        }
        return PipeForgeException.of(ErrorCode.DATA_CONNECTION_LOST)
                .param("peer", peer)
                .param("reason", error.message())
                .cause(cause)
                .build();
    }

    private void failAll(PipeForgeException error) {
        for (RemoteInputChannel target : List.copyOf(channels.values())) {
            if (!target.isEnded()) {
                target.fail(error);
            }
        }
        channels.clear();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        PipeForgeException lost = PipeForgeException.of(ErrorCode.DATA_CONNECTION_LOST)
                .param("peer", peer)
                .param("reason", "the connection closed")
                .build();
        ready.completeExceptionally(lost);
        if (!closing) {
            failAll(lost);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(new Frame.Ping());
            } else if (idle.state() == IdleState.READER_IDLE) {
                LOG.warn("No data and no ping from {} for three intervals; closing the connection", peer);
                ctx.close();
            }
            return;
        }
        ctx.fireUserEventTriggered(event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        PipeForgeException error = Handshake.protocolError(cause);
        if (error != null) {
            LOG.warn("Closing the data connection to {}: {}", peer, error.getMessage());
            ready.completeExceptionally(error);
            failAll(error);
            Handshake.refuse(ctx, error);
            return;
        }
        LOG.warn("The data connection to {} failed", peer, cause);
        ctx.close();
    }

    @Override
    public void requestPartition(RemoteInputChannel target) {
        channels.put(target.id(), target);
        write(new Frame.PartitionRequest(
                target.id(), target.partition(), target.subpartition(), target.requestCredit()));
    }

    @Override
    public void addCredit(RemoteInputChannel target, int credit) {
        write(new Frame.AddCredit(target.id(), credit));
    }

    @Override
    public void cancel(RemoteInputChannel target) {
        write(new Frame.CancelPartition(target.id()));
    }

    @Override
    public void unregister(RemoteInputChannel target) {
        channels.remove(target.id());
    }

    private void write(Frame frame) {
        Channel current = channel;
        if (current != null) {
            current.writeAndFlush(frame);
        }
    }

    /** Closes the connection without failing anything: the other worker learns it from CloseRequest. */
    void close() {
        closing = true;
        Channel current = channel;
        if (current != null && current.isActive()) {
            current.writeAndFlush(new Frame.CloseRequest()).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /** Closes the connection as if it broke; for tests of lost connections. */
    void abort() {
        Channel current = channel;
        if (current != null) {
            current.close();
        }
    }
}
