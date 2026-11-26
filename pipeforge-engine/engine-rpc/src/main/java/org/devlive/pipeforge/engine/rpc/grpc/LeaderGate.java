/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/**
 * Lets only the leading master take calls (protocol specification, section 2.8): a standby refuses
 * registrations, heartbeats and the requests of servers with {@code FAILED_PRECONDITION} and {@code
 * PF-CLUS-0018 NOT_LEADER}, naming the leader when it knows it. Services that answer the same on
 * every master, such as the plugin packages, stay open.
 */
public final class LeaderGate implements ServerInterceptor {

    private final BooleanSupplier leading;
    private final Supplier<String> leader;
    private final Supplier<String> self;
    private final Set<String> open;

    /**
     * @param leading whether this master leads now
     * @param leader where the leader is, empty when unknown
     * @param self this master's address, for the error
     * @param open the services that take calls on a standby too, by their full name
     */
    public LeaderGate(BooleanSupplier leading, Supplier<String> leader, Supplier<String> self, Set<String> open) {
        this.leading = leading;
        this.leader = leader;
        this.self = self;
        this.open = Set.copyOf(open);
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        if (leading.getAsBoolean() || open.contains(call.getMethodDescriptor().getServiceName())) {
            return next.startCall(call, headers);
        }
        var refused = RpcErrors.toStatus(PipeForgeException.of(ErrorCode.NOT_LEADER)
                .param("master", self.get())
                .param("leader", leader.get())
                .build());
        call.close(refused.getStatus(), refused.getTrailers());
        return new ServerCall.Listener<>() {};
    }
}
