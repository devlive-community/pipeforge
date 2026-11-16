/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.netty;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/** What both ends of a connection check and how they refuse a peer. */
final class Handshake {

    private Handshake() {}

    /** Why a Hello is refused, or {@code null} when it is accepted. */
    static @Nullable PipeForgeException check(Frame.Hello hello, String token, String peer) {
        if (!MessageDigest.isEqual(
                hello.clusterToken().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return PipeForgeException.of(ErrorCode.RPC_UNAUTHENTICATED)
                    .param("peer", peer)
                    .build();
        }
        String version = hello.protocolVersion();
        if (!version.startsWith(Frame.VERSION + ".")) {
            return PipeForgeException.of(ErrorCode.RPC_VERSION_MISMATCH)
                    .param("local", Frame.PROTOCOL_VERSION)
                    .param("remote", version)
                    .build();
        }
        return null;
    }

    /** Sends the error for the whole connection and closes it. */
    static void refuse(ChannelHandlerContext ctx, PipeForgeException error) {
        ctx.writeAndFlush(new Frame.ErrorResponse(null, error.code().id(), error.localizedMessage(null)))
                .addListener(ChannelFutureListener.CLOSE);
    }

    /** The protocol error behind an exception of the pipeline, if it is one. */
    static @Nullable PipeForgeException protocolError(Throwable cause) {
        Throwable error = cause instanceof DecoderException && cause.getCause() != null ? cause.getCause() : cause;
        return error instanceof FrameException frame ? frame.error() : null;
    }
}
