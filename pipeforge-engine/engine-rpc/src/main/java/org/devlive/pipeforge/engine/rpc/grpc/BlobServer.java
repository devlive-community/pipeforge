/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.proto.BlobChunk;
import org.devlive.pipeforge.engine.rpc.proto.BlobRequest;
import org.devlive.pipeforge.engine.rpc.proto.BlobServiceGrpc;

/**
 * Serves blobs by their SHA-256 in chunks (protocol v1, BlobService): the plugin packages the
 * master has. A request may start at an offset to continue a download that broke off.
 */
public final class BlobServer extends BlobServiceGrpc.BlobServiceImplBase {

    /** The size of a chunk. */
    static final int CHUNK = 256 * 1024;

    /** Where the blobs are. */
    @FunctionalInterface
    public interface Source {
        /** The file of the blob with this SHA-256, if there is one. */
        Optional<Path> find(String sha256);
    }

    private final Source source;

    public BlobServer(Source source) {
        this.source = source;
    }

    @Override
    public void getBlob(BlobRequest request, StreamObserver<BlobChunk> observer) {
        try {
            Headers.checkVersion(request.getHeader());
            Path file = source.find(request.getSha256())
                    .orElseThrow(() -> PipeForgeException.of(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE)
                            .param("plugin", request.getSha256())
                            .param("reason", "the master has no blob with this SHA-256")
                            .build());
            long size = Files.size(file);
            long offset = Math.max(0, Math.min(request.getOffset(), size));
            try (InputStream in = Files.newInputStream(file)) {
                in.skipNBytes(offset);
                byte[] buffer = new byte[CHUNK];
                long position = offset;
                int read;
                do {
                    read = in.readNBytes(buffer, 0, buffer.length);
                    observer.onNext(BlobChunk.newBuilder()
                            .setOffset(position)
                            .setData(ByteString.copyFrom(buffer, 0, read))
                            .setSize(size)
                            .build());
                    position += read;
                } while (read == buffer.length);
            }
        } catch (IOException | RuntimeException e) {
            observer.onError(RpcErrors.toStatus(e));
            return;
        }
        observer.onCompleted();
    }
}
