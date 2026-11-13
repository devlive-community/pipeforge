/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import io.grpc.Channel;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.proto.BlobChunk;
import org.devlive.pipeforge.engine.rpc.proto.BlobRequest;
import org.devlive.pipeforge.engine.rpc.proto.BlobServiceGrpc;

/**
 * Downloads a blob from the master's BlobService and checks its SHA-256. A download that breaks
 * off (the master cannot be reached) continues from the bytes it has, up to three times; a refusal
 * is not retried, and a blob whose hash does not match is discarded.
 */
public final class BlobClient {

    static final int ATTEMPTS = 4;
    static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final BlobServiceGrpc.BlobServiceBlockingStub stub;

    public BlobClient(Channel channel) {
        this.stub = BlobServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Writes the blob {@code sha256} to {@code target}.
     *
     * @param name how errors name the blob, e.g. the plugin
     * @throws PipeForgeException {@link ErrorCode#PLUGIN_PACKAGE_UNAVAILABLE} when it cannot be
     *     downloaded, {@link ErrorCode#PLUGIN_PACKAGE_CORRUPT} when its hash does not match
     */
    public void fetch(String sha256, String name, Path target) {
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(partial);
            RuntimeException last = null;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                try {
                    download(sha256, partial);
                    last = null;
                    break;
                } catch (io.grpc.StatusRuntimeException e) {
                    PipeForgeException error = RpcErrors.fromStatus(e, "the master");
                    if (!error.code().equals(ErrorCode.RPC_UNAVAILABLE)) {
                        // refused by the master: trying again gives the same answer
                        Object reason = error.params().get("reason");
                        throw PipeForgeException.of(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE)
                                .param("plugin", name)
                                .param("reason", reason == null ? error.getMessage() : String.valueOf(reason))
                                .cause(error)
                                .build();
                    }
                    last = e;
                }
            }
            if (last != null) {
                throw PipeForgeException.of(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE)
                        .param("plugin", name)
                        .param(
                                "reason",
                                RpcErrors.fromStatus(last, "the master").getMessage())
                        .cause(last)
                        .build();
            }
            String actual = sha256(partial);
            if (!actual.equals(sha256)) {
                Files.deleteIfExists(partial);
                throw PipeForgeException.of(ErrorCode.PLUGIN_PACKAGE_CORRUPT)
                        .param("plugin", name)
                        .param("expected", sha256)
                        .param("actual", actual)
                        .build();
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw PipeForgeException.of(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE)
                    .param("plugin", name)
                    .param("reason", "cannot write " + partial + ": " + e.getMessage())
                    .cause(e)
                    .build();
        }
    }

    /** Appends the blob from the bytes {@code partial} has already. */
    private void download(String sha256, Path partial) throws IOException {
        long offset = Files.exists(partial) ? Files.size(partial) : 0;
        Iterator<BlobChunk> chunks = stub.withDeadlineAfter(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .getBlob(BlobRequest.newBuilder()
                        .setHeader(Headers.create(new Headers.Epochs(0, 0), TIMEOUT, System::currentTimeMillis))
                        .setSha256(sha256)
                        .setOffset(offset)
                        .build());
        try (OutputStream out = Files.newOutputStream(
                partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (chunks.hasNext()) {
                chunks.next().getData().writeTo(out);
            }
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
