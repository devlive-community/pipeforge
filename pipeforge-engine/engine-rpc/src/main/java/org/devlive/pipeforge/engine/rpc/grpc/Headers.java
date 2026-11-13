/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ProtocolVersion;
import org.devlive.pipeforge.engine.rpc.proto.Header;

/** The {@link Header} every request starts with, and its checks on the receiving side. */
public final class Headers {

    private Headers() {}

    /**
     * The epochs of the master that sends requests: a new master, or a new job master of a job,
     * always has larger ones. In this version there is one master, so both are 1.
     */
    public record Epochs(long leader, long job) {
        public static final Epochs FIRST = new Epochs(1, 1);
    }

    /** A header with a new request ID and a deadline {@code timeout} from now. */
    static Header create(Epochs epochs, Duration timeout, LongSupplier wallClockMillis) {
        return Header.newBuilder()
                .setProtocolVersion(ProtocolVersion.CURRENT.toString())
                .setLeaderEpoch(epochs.leader())
                .setJobEpoch(epochs.job())
                .setRequestId(UUID.randomUUID().toString())
                .setDeadlineMs(wallClockMillis.getAsLong() + timeout.toMillis())
                .build();
    }

    /**
     * Checks that the other side speaks the same major version.
     *
     * @throws PipeForgeException {@link ErrorCode#RPC_VERSION_MISMATCH} if it does not
     */
    static void checkVersion(Header header) {
        ProtocolVersion remote;
        try {
            remote = ProtocolVersion.parse(header.getProtocolVersion());
        } catch (IllegalArgumentException e) {
            throw mismatch(header.getProtocolVersion());
        }
        if (ProtocolVersion.CURRENT.negotiate(remote).isEmpty()) {
            throw mismatch(remote.toString());
        }
    }

    private static PipeForgeException mismatch(String remote) {
        return PipeForgeException.of(ErrorCode.RPC_VERSION_MISMATCH)
                .param("local", ProtocolVersion.CURRENT)
                .param("remote", remote.isEmpty() ? "none" : remote)
                .build();
    }
}
