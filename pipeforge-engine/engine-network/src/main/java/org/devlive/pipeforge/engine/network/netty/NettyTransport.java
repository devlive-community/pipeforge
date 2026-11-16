/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.NetworkOptions;
import org.devlive.pipeforge.engine.network.gate.RemoteInputChannel;
import org.devlive.pipeforge.engine.network.partition.ResultPartitionManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Netty side of the data exchange of a worker: the server other workers read from, and one
 * shared connection to each worker this one reads from.
 */
// Netty reports a failed write through the pipeline (exceptionCaught, the close future), not
// through the future of the write.
@SuppressWarnings("FutureReturnValueIgnored")
public final class NettyTransport implements RemoteInputChannel.Connector, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NettyTransport.class);

    private final NetworkOptions options;
    private final String workerId;
    private final String token;
    private final EventLoopGroup group;
    private final Map<InetSocketAddress, CompletableFuture<ClientConnection>> connections = new ConcurrentHashMap<>();
    private final List<Channel> accepted = new ArrayList<>();
    private @Nullable Channel server;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    public NettyTransport(NetworkOptions options, String workerId, String token, int threads) {
        this.options = options;
        this.workerId = workerId;
        this.token = token;
        this.group = new MultiThreadIoEventLoopGroup(threads, NioIoHandler.newFactory());
    }

    /** A timer of the event loops, for timeouts and the buffer timeout. */
    public ScheduledExecutorService timer() {
        return group.next();
    }

    /** Listens for other workers; port 0 takes a free port. */
    public InetSocketAddress bind(String host, int port, ResultPartitionManager partitions) {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        synchronized (accepted) {
                            accepted.add(channel);
                        }
                        pipeline(channel.pipeline()).addLast(new ServerHandler(partitions, workerId, token));
                    }
                });
        ChannelFuture bound = bootstrap.bind(host, port).awaitUninterruptibly();
        if (!bound.isSuccess()) {
            throw PipeForgeException.of(ErrorCode.PORT_IN_USE)
                    .param("port", port)
                    .cause(bound.cause())
                    .build();
        }
        Channel channel = bound.channel();
        server = channel;
        return (InetSocketAddress) channel.localAddress();
    }

    private ChannelPipeline pipeline(ChannelPipeline pipeline) {
        long ping = options.pingInterval().toMillis();
        return pipeline.addLast(new IdleStateHandler(3 * ping, ping, 0, TimeUnit.MILLISECONDS))
                .addLast(new FrameCodec.Decoder(options.maxFrameBytes()))
                .addLast(new FrameCodec.Encoder());
    }

    /** The shared connection to the worker at {@code address}, opened when there is none. */
    @Override
    public CompletableFuture<ClientConnection> connect(InetSocketAddress address) {
        while (true) {
            CompletableFuture<ClientConnection> existing = connections.get(address);
            if (existing != null && !existing.isCompletedExceptionally()) {
                return existing;
            }
            CompletableFuture<ClientConnection> created = new CompletableFuture<>();
            boolean ours = existing == null
                    ? connections.putIfAbsent(address, created) == null
                    : connections.replace(address, existing, created);
            if (ours) {
                open(address, created);
                return created;
            }
        }
    }

    private void open(InetSocketAddress address, CompletableFuture<ClientConnection> result) {
        ClientConnection connection = new ClientConnection(address, workerId, token, options.partitionRequestTimeout());
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int)
                        options.connectTimeout().toMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        pipeline(channel.pipeline()).addLast(connection);
                    }
                });
        bootstrap.connect(address).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                result.completeExceptionally(PipeForgeException.of(ErrorCode.DATA_CONNECTION_LOST)
                        .param("peer", address.toString())
                        .param("reason", "cannot connect: " + f.cause().getMessage())
                        .cause(f.cause())
                        .build());
                return;
            }
            f.channel().closeFuture().addListener(c -> connections.remove(address, result));
        });
        connection.ready().whenComplete((ok, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(ok);
            }
        });
    }

    /** Connections this worker holds to others. */
    public int openConnections() {
        return (int) connections.values().stream()
                .filter(f -> f.isDone() && !f.isCompletedExceptionally())
                .count();
    }

    /** Breaks the connection to {@code address} as a failed network would; for tests. */
    public void abort(InetSocketAddress address) {
        CompletableFuture<ClientConnection> connection = connections.get(address);
        if (connection != null && connection.isDone() && !connection.isCompletedExceptionally()) {
            connection.join().abort();
        }
    }

    /** Breaks every connection other workers opened to this one; for tests. */
    public void abortAccepted() {
        synchronized (accepted) {
            accepted.forEach(Channel::close);
        }
    }

    /** Closes the connections and the server; closing it again does nothing. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (CompletableFuture<ClientConnection> connection : connections.values()) {
            if (connection.isDone() && !connection.isCompletedExceptionally()) {
                connection.join().close();
            }
        }
        Channel current = server;
        if (current != null) {
            current.close().syncUninterruptibly();
        }
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        LOG.debug("Stopped the data exchange of {}", workerId);
    }
}
