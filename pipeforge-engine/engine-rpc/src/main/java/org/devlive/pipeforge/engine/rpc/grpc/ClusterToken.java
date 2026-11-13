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
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * The shared token of a cluster, sent with every call in the metadata {@code
 * pipeforge-cluster-token}; a server refuses calls without it ({@code UNAUTHENTICATED}).
 */
public final class ClusterToken {

    static final Metadata.Key<String> KEY =
            Metadata.Key.of("pipeforge-cluster-token", Metadata.ASCII_STRING_MARSHALLER);

    private final byte[] token;

    /**
     * @throws IllegalArgumentException if the token is blank
     */
    public ClusterToken(String token) {
        if (token.isBlank()) {
            throw new IllegalArgumentException("The cluster token must not be blank");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Adds the token to every call of a client. */
    public ClientInterceptor client() {
        String value = new String(token, StandardCharsets.UTF_8);
        return new ClientInterceptor() {
            @Override
            public <Q, R> ClientCall<Q, R> interceptCall(
                    MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                    @Override
                    public void start(Listener<R> listener, Metadata headers) {
                        headers.put(KEY, value);
                        super.start(listener, headers);
                    }
                };
            }
        };
    }

    /** Refuses calls without the token. */
    public ServerInterceptor server() {
        return new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(
                    ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                String presented = headers.get(KEY);
                if (presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
                    SocketAddress peer = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
                    var refused = RpcErrors.toStatus(PipeForgeException.of(ErrorCode.RPC_UNAUTHENTICATED)
                            .param("peer", peer == null ? "a client" : peer.toString())
                            .build());
                    call.close(refused.getStatus(), refused.getTrailers());
                    return new ServerCall.Listener<>() {};
                }
                return next.startCall(call, headers);
            }
        };
    }
}
