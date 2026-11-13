/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ChainCodecTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The control RPC over TCP with Netty, as between processes: with TLS, and refused when a client
 * does not trust the server or does not use TLS. The certificates are made by keytool for the test.
 */
class GrpcTlsTest {

    static final String PASSWORD = "changeit";

    @TempDir
    static Path dir;

    static GrpcTransport.Tls tls;
    static GrpcTransport.Tls foreign;

    final List<AutoCloseable> closing = new ArrayList<>();
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    final Retry retry = new Retry(scheduler, Duration.ofMillis(5));

    @BeforeAll
    static void certificates() throws Exception {
        tls = certificate("server");
        foreign = certificate("other");
    }

    /** A key store with a self-signed certificate for localhost, and a trust store with only it. */
    static GrpcTransport.Tls certificate(String name) throws Exception {
        Path keys = dir.resolve(name + "-keys.p12");
        Path cert = dir.resolve(name + ".crt");
        Path trust = dir.resolve(name + "-trust.p12");
        keytool(
                "-genkeypair",
                "-alias",
                name,
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-validity",
                "2",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype",
                "PKCS12",
                "-keystore",
                keys.toString(),
                "-storepass",
                PASSWORD);
        keytool(
                "-exportcert",
                "-rfc",
                "-alias",
                name,
                "-keystore",
                keys.toString(),
                "-storepass",
                PASSWORD,
                "-file",
                cert.toString());
        keytool(
                "-importcert",
                "-noprompt",
                "-alias",
                name,
                "-file",
                cert.toString(),
                "-storetype",
                "PKCS12",
                "-keystore",
                trust.toString(),
                "-storepass",
                PASSWORD);
        return new GrpcTransport.Tls(keys, PASSWORD, trust, PASSWORD);
    }

    static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closing) {
            c.close();
        }
        scheduler.shutdownNow();
    }

    int serve(GrpcTransport.Security security, GrpcTransportTest.Worker worker) throws IOException {
        Server server = GrpcTransport.serve(
                0,
                security,
                List.of(new TaskExecutorService(
                        worker,
                        SecretBox.generate(),
                        ChainCodecTest.Fake::new,
                        new Fencing(),
                        new IdempotencyCache())));
        closing.add(() -> server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS));
        return server.getPort();
    }

    TaskExecutorClient client(int port, GrpcTransport.Security security) throws IOException {
        ManagedChannel channel = GrpcTransport.channel("localhost:" + port, security);
        closing.add(() -> channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS));
        return new TaskExecutorClient(
                channel, "localhost:" + port, SecretBox.generate().publicKey(), () -> Headers.Epochs.FIRST, retry);
    }

    static GrpcTransport.Security security(GrpcTransport.Tls tls) {
        return new GrpcTransport.Security(new ClusterToken("cluster-secret"), tls);
    }

    @Test
    void callsTravelOverTls() throws Exception {
        GrpcTransportTest.Worker worker = new GrpcTransportTest.Worker();
        int port = serve(security(tls), worker);
        client(port, security(tls)).cancelTask(GrpcTransportTest.TASK).get(10, TimeUnit.SECONDS);
        assertThat(worker.cancelled).containsExactly(GrpcTransportTest.TASK);
    }

    @Test
    void callsTravelOverPlainTcpWithoutTls() throws Exception {
        GrpcTransportTest.Worker worker = new GrpcTransportTest.Worker();
        int port = serve(security(null), worker);
        client(port, security(null)).cancelTask(GrpcTransportTest.TASK).get(10, TimeUnit.SECONDS);
        assertThat(worker.cancelled).hasSize(1);
    }

    @Test
    void clientsThatDoNotTrustTheServerOrDoNotUseTlsGetNoAnswer() throws Exception {
        GrpcTransportTest.Worker worker = new GrpcTransportTest.Worker();
        int port = serve(security(tls), worker);

        for (GrpcTransport.Security client : List.of(security(foreign), security(null))) {
            PipeForgeException error =
                    GrpcTransportTest.failure(client(port, client).cancelTask(GrpcTransportTest.TASK));
            assertThat(error.code()).isEqualTo(ErrorCode.RPC_UNAVAILABLE);
            assertThat(error.params()).containsEntry("target", "localhost:" + port);
        }
        assertThat(worker.cancelled).isEmpty();
    }
}
