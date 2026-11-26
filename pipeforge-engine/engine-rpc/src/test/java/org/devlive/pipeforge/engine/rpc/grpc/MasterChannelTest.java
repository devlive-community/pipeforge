/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.proto.BlobChunk;
import org.devlive.pipeforge.engine.rpc.proto.BlobRequest;
import org.devlive.pipeforge.engine.rpc.proto.BlobServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Calls that follow the leader among the masters of a cluster. */
class MasterChannelTest {

    static final GrpcTransport.Security SECURITY = new GrpcTransport.Security(GrpcTransportTest.TOKEN, null);

    final List<Server> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(Server::shutdownNow);
    }

    /** A master that answers with its name while {@code leading}, and names {@code leader} otherwise. */
    String master(String name, AtomicBoolean leading, String leader) throws Exception {
        int port = freePort();
        String address = "localhost:" + port;
        servers.add(GrpcTransport.serve(
                port,
                SECURITY,
                List.of(new BlobServiceGrpc.BlobServiceImplBase() {
                    @Override
                    public void getBlob(BlobRequest request, StreamObserver<BlobChunk> answers) {
                        answers.onNext(BlobChunk.newBuilder()
                                .setData(com.google.protobuf.ByteString.copyFromUtf8(name))
                                .build());
                        answers.onCompleted();
                    }
                }),
                List.of(new LeaderGate(leading::get, () -> leader, () -> address, Set.of()))));
        return address;
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static String ask(MasterChannel channel) {
        var answers = BlobServiceGrpc.newBlockingStub(channel).getBlob(BlobRequest.getDefaultInstance());
        return answers.next().getData().toStringUtf8();
    }

    MasterChannel channel(List<String> addresses) {
        return new MasterChannel(addresses, address -> GrpcTransport.channel(address, SECURITY));
    }

    @Test
    void aStandbyNamesTheLeaderAndTheNextCallGoesThere() throws Exception {
        AtomicBoolean first = new AtomicBoolean(false);
        AtomicBoolean second = new AtomicBoolean(true);
        int leaderPort = freePort();
        String a = master("a", first, "localhost:" + leaderPort);
        servers.add(GrpcTransport.serve(
                leaderPort,
                SECURITY,
                List.of(new BlobServiceGrpc.BlobServiceImplBase() {
                    @Override
                    public void getBlob(BlobRequest request, StreamObserver<BlobChunk> answers) {
                        answers.onNext(BlobChunk.newBuilder()
                                .setData(com.google.protobuf.ByteString.copyFromUtf8("b"))
                                .build());
                        answers.onCompleted();
                    }
                }),
                List.of(new LeaderGate(second::get, () -> "", () -> "b", Set.of()))));
        String b = "localhost:" + leaderPort;
        try (MasterChannel channel = channel(List.of(a, b))) {
            assertThat(channel.address()).isEqualTo(a);
            assertThatThrownBy(() -> ask(channel)).satisfies(e -> {
                PipeForgeException refused = RpcErrors.fromStatus(e, a);
                assertThat(refused.code()).isEqualTo(ErrorCode.NOT_LEADER);
                assertThat(refused.params()).containsEntry("leader", b).containsEntry("master", a);
            });
            assertThat(channel.address()).isEqualTo(b);
            assertThat(ask(channel)).isEqualTo("b");

            // the leaders change places: a standby that knows no leader sends calls on to the next
            second.set(false);
            first.set(true);
            assertThatThrownBy(() -> ask(channel)).isNotNull();
            assertThat(channel.address()).isEqualTo(a);
            assertThat(ask(channel)).isEqualTo("a");
        }
    }

    @Test
    void aMasterThatDoesNotAnswerIsSkipped() throws Exception {
        String gone = "localhost:" + freePort();
        String live = master("live", new AtomicBoolean(true), "");
        try (MasterChannel channel = channel(MasterChannel.addresses(gone + ", " + live + ","))) {
            assertThat(channel.addresses()).containsExactly(gone, live);
            assertThatThrownBy(() -> ask(channel)).isNotNull();
            assertThat(channel.address()).isEqualTo(live);
            assertThat(ask(channel)).isEqualTo("live");
            assertThat(channel.authority()).isNotBlank();
        }
        try (MasterChannel alone = channel(List.of(gone))) {
            assertThatThrownBy(() -> ask(alone)).isNotNull();
            assertThat(alone.address()).as("one master: nowhere else to go").isEqualTo(gone);
        }
        assertThatThrownBy(() -> new MasterChannel(List.of(), address -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fencingLearnsTheEpochOfANewLeader() {
        Fencing fencing = new Fencing();
        fencing.learnLeader(3);
        fencing.learnLeader(2);
        assertThatThrownBy(() -> fencing.checkLeader(org.devlive.pipeforge.engine.rpc.proto.Header.newBuilder()
                        .setLeaderEpoch(2)
                        .build()))
                .isInstanceOfSatisfying(
                        PipeForgeException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RPC_STALE_EPOCH));
        fencing.checkLeader(org.devlive.pipeforge.engine.rpc.proto.Header.newBuilder()
                .setLeaderEpoch(3)
                .build());
    }
}
