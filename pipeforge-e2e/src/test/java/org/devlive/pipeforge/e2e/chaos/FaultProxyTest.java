/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Links cut, slowed down and losing packets in the proxy. */
class FaultProxyTest {

    ServerSocket echo;
    FaultProxy proxy;

    @BeforeEach
    void start() throws IOException {
        echo = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread server = new Thread(() -> {
            while (!echo.isClosed()) {
                try {
                    Socket socket = echo.accept();
                    Thread client = new Thread(() -> {
                        try (socket;
                                InputStream in = socket.getInputStream();
                                OutputStream out = socket.getOutputStream()) {
                            in.transferTo(out);
                        } catch (IOException e) {
                            // closed
                        }
                    });
                    client.setDaemon(true);
                    client.start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        server.setDaemon(true);
        server.start();
        proxy = new FaultProxy(
                "a->b", 0, new InetSocketAddress(InetAddress.getLoopbackAddress(), echo.getLocalPort()), 7);
    }

    @AfterEach
    void stop() throws IOException {
        proxy.close();
        echo.close();
    }

    Socket connect() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxy.port());
        socket.setSoTimeout(5_000);
        return socket;
    }

    /** Sends a byte and returns how long its echo took, in milliseconds. */
    static long roundTrip(Socket socket) throws IOException {
        long started = System.nanoTime();
        socket.getOutputStream().write(1);
        socket.getOutputStream().flush();
        assertThat(socket.getInputStream().read()).isEqualTo(1);
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    @Test
    void passesBytesOnAndDelaysThemWhenAsked() throws IOException {
        try (Socket socket = connect()) {
            assertThat(roundTrip(socket)).isLessThan(150);
            proxy.delay(150);
            // both directions wait
            assertThat(roundTrip(socket)).isGreaterThanOrEqualTo(300);
            proxy.heal();
            assertThat(roundTrip(socket)).isLessThan(150);
        }
        assertThat(proxy.name()).isEqualTo("a->b");
        assertThat(proxy.toString()).contains("a->b");
    }

    @Test
    void aPartitionDropsTheConnectionsAndRefusesNewOnesUntilItHeals() throws Exception {
        Socket socket = connect();
        roundTrip(socket);
        assertThat(proxy.connections()).isEqualTo(1);
        proxy.partition();
        assertThat(socket.getInputStream().read()).as("the connection is gone").isEqualTo(-1);
        socket.close();
        try (Socket refused = connect()) {
            assertThat(refused.getInputStream().read()).as("refused at once").isEqualTo(-1);
        }
        proxy.heal();
        try (Socket again = connect()) {
            assertThat(roundTrip(again)).isLessThan(500);
        }
    }

    @Test
    void lostPacketsWaitAsForAResend() throws IOException {
        proxy.loss(100);
        try (Socket socket = connect()) {
            assertThat(roundTrip(socket)).isGreaterThanOrEqualTo(2 * FaultProxy.RESEND_MS);
        }
        proxy.loss(0);
        try (Socket socket = connect()) {
            assertThat(roundTrip(socket)).isLessThan(FaultProxy.RESEND_MS);
        }
    }
}
