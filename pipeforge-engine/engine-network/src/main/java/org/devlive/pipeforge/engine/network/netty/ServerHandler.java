/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.devlive.pipeforge.engine.network.partition.ResultPartitionManager;
import org.devlive.pipeforge.engine.network.partition.SubpartitionView;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The producer end of one connection: it serves the subpartitions consumers ask for and sends
 * their buffers while the channels have credit. All its state is used on the event loop of the
 * connection.
 */
// Netty reports a failed write through the pipeline (exceptionCaught, the close future), not
// through the future of the write.
@SuppressWarnings("FutureReturnValueIgnored")
final class ServerHandler extends SimpleChannelInboundHandler<Frame> {

    private static final Logger LOG = LoggerFactory.getLogger(ServerHandler.class);

    /** A subpartition sent over this connection. */
    private static final class Outbound {
        final UUID channel;
        int credit;
        long sequence;

        @Nullable
        SubpartitionView view;

        boolean done;

        Outbound(UUID channel, int credit) {
            this.channel = channel;
            this.credit = credit;
        }
    }

    private final ResultPartitionManager partitions;
    private final String workerId;
    private final String token;
    private final Map<UUID, Outbound> outbound = new HashMap<>();
    private boolean hello;
    private boolean closing;
    private String peer = "a worker";

    ServerHandler(ResultPartitionManager partitions, String workerId, String token) {
        this.partitions = partitions;
        this.workerId = workerId;
        this.token = token;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        peer = String.valueOf(ctx.channel().remoteAddress());
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        if (!hello && !(frame instanceof Frame.Hello)) {
            release(frame);
            Handshake.refuse(
                    ctx,
                    FrameCodec.malformed("a " + frame.getClass().getSimpleName() + " before Hello")
                            .error());
            return;
        }
        switch (frame) {
            case Frame.Hello h -> {
                PipeForgeException refused = Handshake.check(h, token, peer);
                if (refused != null) {
                    LOG.warn("Refused the data connection of {}: {}", peer, refused.getMessage());
                    Handshake.refuse(ctx, refused);
                    return;
                }
                hello = true;
                peer = h.workerId() + " (" + ctx.channel().remoteAddress() + ")";
                ctx.writeAndFlush(new Frame.Hello(Frame.PROTOCOL_VERSION, workerId, token));
            }
            case Frame.PartitionRequest request -> request(ctx, request);
            case Frame.AddCredit credit -> {
                Outbound target = outbound.get(credit.channel());
                if (target != null) {
                    target.credit += credit.credit();
                    send(ctx, target);
                }
            }
            case Frame.CancelPartition cancel -> {
                Outbound target = outbound.remove(cancel.channel());
                if (target != null && target.view != null) {
                    target.done = true;
                    target.view.release();
                }
            }
            case Frame.CloseRequest close -> {
                closing = true;
                ctx.close();
            }
            case Frame.Ping ping -> ctx.writeAndFlush(new Frame.Pong());
            case Frame.Pong pong -> {}
            case Frame.ErrorResponse error -> {
                LOG.warn("{} reported an error: {} {}", peer, error.code(), error.message());
                if (error.channel() == null) {
                    ctx.close();
                }
            }
            case Frame.BufferResponse response -> {
                response.data().release();
                Handshake.refuse(
                        ctx,
                        FrameCodec.malformed("a buffer sent to the producer").error());
            }
        }
    }

    private void request(ChannelHandlerContext ctx, Frame.PartitionRequest request) {
        Outbound target = new Outbound(request.channel(), request.credit());
        outbound.put(request.channel(), target);
        partitions.partition(request.partition(), ctx.executor()).whenComplete((partition, error) -> ctx.executor()
                .execute(() -> {
                    if (outbound.get(request.channel()) != target) {
                        return;
                    }
                    if (error != null) {
                        outbound.remove(request.channel());
                        PipeForgeException e =
                                error instanceof PipeForgeException p ? p : PipeForgeException.wrap(error);
                        ctx.writeAndFlush(new Frame.ErrorResponse(
                                request.channel(), e.code().id(), e.localizedMessage(null)));
                        return;
                    }
                    try {
                        target.view = partition.createView(
                                request.subpartition(), () -> ctx.executor().execute(() -> send(ctx, target)));
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        outbound.remove(request.channel());
                        ctx.writeAndFlush(
                                new Frame.ErrorResponse(request.channel(), ErrorCode.INTERNAL.id(), e.getMessage()));
                        return;
                    }
                    send(ctx, target);
                }));
    }

    /** Sends what the channel has credit for, and every event. */
    private void send(ChannelHandlerContext ctx, Outbound target) {
        SubpartitionView view = target.view;
        if (view == null || target.done || !ctx.channel().isActive()) {
            return;
        }
        boolean wrote = false;
        while (ctx.channel().isWritable()) {
            SubpartitionView.Delivery delivery = view.poll(target.credit > 0);
            if (delivery == null) {
                break;
            }
            Buffer buffer = delivery.buffer();
            if (!buffer.isEvent()) {
                target.credit--;
            }
            ctx.write(new Frame.BufferResponse(
                            target.channel,
                            target.sequence++,
                            delivery.backlog(),
                            buffer.kind(),
                            0,
                            Unpooled.wrappedBuffer(buffer.memory(), 0, buffer.size())))
                    .addListener(f -> buffer.recycle());
            wrote = true;
            if (delivery.last()) {
                target.done = true;
                outbound.remove(target.channel);
                break;
            }
        }
        if (wrote) {
            ctx.flush();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) {
            new ArrayList<>(outbound.values()).forEach(target -> send(ctx, target));
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        for (Outbound target : outbound.values()) {
            SubpartitionView view = target.view;
            if (view == null || target.done) {
                continue;
            }
            if (!closing) {
                view.partition()
                        .fail(PipeForgeException.of(ErrorCode.DATA_CONNECTION_LOST)
                                .param("peer", peer)
                                .param("reason", "the connection of the consumer closed")
                                .build());
            }
            view.release();
        }
        outbound.clear();
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
            LOG.warn("Closing the data connection of {}: {}", peer, error.getMessage());
            Handshake.refuse(ctx, error);
            return;
        }
        LOG.warn("The data connection of {} failed", peer, cause);
        ctx.close();
    }

    private static void release(Frame frame) {
        if (frame instanceof Frame.BufferResponse response) {
            response.data().release();
        }
    }
}
