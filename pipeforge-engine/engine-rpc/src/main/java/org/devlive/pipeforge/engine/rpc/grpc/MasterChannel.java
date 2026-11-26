/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.engine.rpc.proto.ErrorInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A channel to the leading master of a cluster with several (protocol specification, section 2.8).
 * Calls go to one master at a time. A call a standby refused with {@code PF-CLUS-0018 NOT_LEADER}
 * moves the channel to the leader it named, or to the next configured master when it named none;
 * a call the master did not answer ({@code UNAVAILABLE}) moves it to the next one. The call itself
 * fails as it did: callers try again, as they do for any master that is away.
 */
public final class MasterChannel extends Channel implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MasterChannel.class);

    /** Opens a channel to one master. */
    @FunctionalInterface
    public interface Opener {
        ManagedChannel open(String address) throws IOException;
    }

    private final List<String> configured;
    private final Opener opener;
    private final Map<String, ManagedChannel> channels = new LinkedHashMap<>();
    private volatile String current;

    /**
     * @param addresses the masters of the cluster, {@code host:port}, tried in this order
     * @throws IllegalArgumentException without an address
     */
    public MasterChannel(List<String> addresses, Opener opener) {
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("at least one master address is needed");
        }
        this.configured = List.copyOf(addresses);
        this.opener = opener;
        this.current = configured.get(0);
    }

    /** {@code host:port,host:port} as a list, blanks dropped. */
    public static List<String> addresses(String list) {
        List<String> addresses = new ArrayList<>();
        for (String part : list.split(",")) {
            if (!part.isBlank()) {
                addresses.add(part.strip());
            }
        }
        return addresses;
    }

    /** The master calls go to now. */
    public String address() {
        return current;
    }

    /** The configured masters. */
    public List<String> addresses() {
        return configured;
    }

    private synchronized ManagedChannel channel(String address) {
        ManagedChannel channel = channels.get(address);
        if (channel == null) {
            try {
                channel = opener.open(address);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            channels.put(address, channel);
        }
        return channel;
    }

    /** Moves to {@code next} when calls still go to {@code from}, so that late answers do not flap. */
    private synchronized void move(String from, String next, String why) {
        if (current.equals(from) && !next.equals(from)) {
            current = next;
            LOG.info("Calls to the master go to {} now: {} {}", next, from, why);
        }
    }

    private String after(String address) {
        int index = configured.indexOf(address);
        return configured.get((index + 1) % configured.size());
    }

    @Override
    public <Q, R> ClientCall<Q, R> newCall(MethodDescriptor<Q, R> method, CallOptions options) {
        String target = current;
        ClientCall<Q, R> call = channel(target).newCall(method, options);
        return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
            @Override
            public void start(Listener<R> listener, Metadata headers) {
                super.start(
                        new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                            @Override
                            public void onClose(Status status, Metadata trailers) {
                                follow(target, status, trailers);
                                super.onClose(status, trailers);
                            }
                        },
                        headers);
            }
        };
    }

    private void follow(String target, Status status, Metadata trailers) {
        if (status.getCode() == Status.Code.UNAVAILABLE && configured.size() > 1) {
            move(target, after(target), "does not answer");
            return;
        }
        if (status.getCode() != Status.Code.FAILED_PRECONDITION) {
            return;
        }
        ErrorInfo info = trailers.get(RpcErrors.ERROR);
        if (info == null || !info.getCode().equals(ErrorCode.NOT_LEADER.id())) {
            return;
        }
        String leader = info.getParamsOrDefault("leader", "");
        move(target, leader.isBlank() ? after(target) : leader, "is a standby");
    }

    @Override
    public String authority() {
        return channel(current).authority();
    }

    @Override
    public synchronized void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
        channels.clear();
    }
}
