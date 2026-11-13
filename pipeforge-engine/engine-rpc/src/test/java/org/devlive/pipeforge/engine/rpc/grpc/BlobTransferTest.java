/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.proto.BlobChunk;
import org.devlive.pipeforge.engine.rpc.proto.BlobRequest;
import org.devlive.pipeforge.engine.rpc.proto.BlobServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Blobs downloaded from the BlobService: in chunks, resumed, checked and refused. */
class BlobTransferTest {

    @TempDir
    Path dir;

    final List<Server> servers = new ArrayList<>();
    final List<ManagedChannel> channels = new ArrayList<>();

    @AfterEach
    void stop() throws InterruptedException {
        channels.forEach(ManagedChannel::shutdownNow);
        for (Server server : servers) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    ManagedChannel serve(BindableService service) throws Exception {
        String name = "blobs-" + UUID.randomUUID();
        servers.add(InProcessServerBuilder.forName(name)
                .addService(service)
                .directExecutor()
                .build()
                .start());
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        channels.add(channel);
        return channel;
    }

    /** A file of {@code size} random bytes and its SHA-256. */
    Map.Entry<String, Path> blob(String name, int size) throws Exception {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        Path file = Files.write(dir.resolve(name), bytes);
        return Map.entry(BlobClient.sha256(file), file);
    }

    @Test
    void downloadsSmallAndLargeBlobsInChunks() throws Exception {
        var small = blob("small", 10);
        var large = blob("large", 2 * BlobServer.CHUNK + 17);
        var empty = blob("empty", 0);
        Map<String, Path> files = Map.ofEntries(small, large, empty);
        BlobClient client = new BlobClient(serve(new BlobServer(sha -> Optional.ofNullable(files.get(sha)))));

        for (var blob : List.of(small, large, empty)) {
            Path target = dir.resolve("got").resolve(blob.getValue().getFileName());
            client.fetch(blob.getKey(), "test", target);
            assertThat(Files.readAllBytes(target)).isEqualTo(Files.readAllBytes(blob.getValue()));
            assertThat(target.resolveSibling(target.getFileName() + ".partial")).doesNotExist();
        }
    }

    @Test
    void theServerStartsAtTheOffsetOfTheRequest() throws Exception {
        var large = blob("large", BlobServer.CHUNK + 100);
        var stub = BlobServiceGrpc.newBlockingStub(serve(new BlobServer(sha -> Optional.of(large.getValue()))));
        List<BlobChunk> chunks = new ArrayList<>();
        stub.getBlob(BlobRequest.newBuilder()
                        .setHeader(header())
                        .setSha256(large.getKey())
                        .setOffset(BlobServer.CHUNK - 10)
                        .build())
                .forEachRemaining(chunks::add);
        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.getOffset()).isEqualTo(BlobServer.CHUNK - 10);
            assertThat(chunk.getData().size()).isEqualTo(110);
            assertThat(chunk.getSize()).isEqualTo(BlobServer.CHUNK + 100);
        });
    }

    /** Sends one chunk of 1000 bytes per call and breaks off, until the blob is complete. */
    static final class Breaking extends BlobServiceGrpc.BlobServiceImplBase {
        final byte[] content;
        final List<Long> offsets = new CopyOnWriteArrayList<>();

        Breaking(byte[] content) {
            this.content = content;
        }

        @Override
        public void getBlob(BlobRequest request, StreamObserver<BlobChunk> observer) {
            int offset = (int) request.getOffset();
            offsets.add(request.getOffset());
            int end = Math.min(content.length, offset + 1000);
            observer.onNext(BlobChunk.newBuilder()
                    .setOffset(offset)
                    .setData(ByteString.copyFrom(Arrays.copyOfRange(content, offset, end)))
                    .setSize(content.length)
                    .build());
            if (end < content.length) {
                observer.onError(
                        Status.UNAVAILABLE.withDescription("connection reset").asRuntimeException());
            } else {
                observer.onCompleted();
            }
        }
    }

    @Test
    void aDownloadThatBreaksOffContinuesFromItsBytes() throws Exception {
        var blob = blob("blob", 3500);
        Breaking service = new Breaking(Files.readAllBytes(blob.getValue()));
        Path target = dir.resolve("got");
        new BlobClient(serve(service)).fetch(blob.getKey(), "test", target);
        assertThat(service.offsets).containsExactly(0L, 1000L, 2000L, 3000L);
        assertThat(Files.readAllBytes(target)).isEqualTo(Files.readAllBytes(blob.getValue()));

        Breaking longer = new Breaking(new byte[5500]);
        assertThatThrownBy(() -> new BlobClient(serve(longer)).fetch(BlobClient.sha256(target), "alpha@1.0", target))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("alpha@1.0").contains("connection reset");
                });
        assertThat(longer.offsets).hasSize(BlobClient.ATTEMPTS);
    }

    @Test
    void aBlobWithAnotherHashIsDiscarded() throws Exception {
        var real = blob("real", 100);
        var other = blob("other", 200);
        BlobClient client = new BlobClient(serve(new BlobServer(sha -> Optional.of(other.getValue()))));
        Path target = dir.resolve("got");
        assertThatThrownBy(() -> client.fetch(real.getKey(), "alpha@1.0", target))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PLUGIN_PACKAGE_CORRUPT);
                    assertThat(e.params())
                            .containsEntry("expected", real.getKey())
                            .containsEntry("actual", other.getKey());
                });
        assertThat(target).doesNotExist();
        assertThat(dir.resolve("got.partial")).doesNotExist();
    }

    @Test
    void refusalsAreNotRetried() throws Exception {
        BlobServer empty = new BlobServer(sha -> Optional.empty());
        BlobClient client = new BlobClient(serve(empty));
        assertThatThrownBy(() -> client.fetch("ab".repeat(32), "alpha@1.0", dir.resolve("got")))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PLUGIN_PACKAGE_UNAVAILABLE);
                    assertThat(e.params())
                            .containsEntry("plugin", "alpha@1.0")
                            .containsEntry("reason", "the master has no blob with this SHA-256");
                });

        var stub = BlobServiceGrpc.newBlockingStub(serve(empty));
        assertThatThrownBy(() -> stub.getBlob(BlobRequest.newBuilder()
                                .setHeader(header().toBuilder().setProtocolVersion("2.0"))
                                .setSha256("x")
                                .build())
                        .hasNext())
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> assertThat(
                                RpcErrors.fromStatus(e, "master").code())
                        .isEqualTo(ErrorCode.RPC_VERSION_MISMATCH));
    }

    static org.devlive.pipeforge.engine.rpc.proto.Header header() {
        return Headers.create(Headers.Epochs.FIRST, Duration.ofSeconds(5), System::currentTimeMillis);
    }
}
