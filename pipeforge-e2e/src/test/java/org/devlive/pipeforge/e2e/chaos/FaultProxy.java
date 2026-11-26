/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A TCP proxy in the test process that one link of the cluster goes through, so that the link can
 * be cut, slowed down or made to lose packets while the processes on both ends run on. Clients
 * connect to {@link #port()}; the proxy connects on to the target.
 *
 * <ul>
 *   <li>a partition closes the connections the link carries and refuses new ones until it heals;
 *   <li>a delay makes every chunk wait before it is passed on;
 *   <li>a loss makes a share of the chunks wait 200 ms, as TCP does when it resends a lost packet.
 * </ul>
 *
 * Which chunks wait is drawn from a random seeded by the run's seed; the plan replays exactly, the
 * chunks that wait depend on how the traffic falls.
 */
public final class FaultProxy implements AutoCloseable {

    /** How long TCP waits before it resends a lost packet, about. */
    static final long RESEND_MS = 200;

    private final String name;
    private final InetSocketAddress target;
    private final ServerSocket server;
    private final ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "chaos-proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private final SplittableRandom random;
    private volatile boolean partitioned;
    private volatile long delayMs;
    private volatile int lossPercent;
    private volatile boolean closed;

    /**
     * Listens on {@code port} of the loopback address (0 for any) and passes on to {@code target}.
     *
     * @param name the link, for thread names and messages
     */
    public FaultProxy(String name, int port, InetSocketAddress target, long seed) throws IOException {
        this.name = name;
        this.target = target;
        this.random = new SplittableRandom(seed);
        this.server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        threads.execute(this::accept);
    }

    public String name() {
        return name;
    }

    /** The port clients connect to. */
    public int port() {
        return server.getLocalPort();
    }

    /** Closes the connections of the link and refuses new ones. */
    public void partition() {
        partitioned = true;
        for (Socket socket : open) {
            closeQuietly(socket);
        }
        open.clear();
    }

    /** Makes every chunk wait {@code millis}. */
    public void delay(long millis) {
        delayMs = millis;
    }

    /** Makes {@code percent} of the chunks wait as for a resent packet. */
    public void loss(int percent) {
        lossPercent = percent;
    }

    /** The link works as before. */
    public void heal() {
        partitioned = false;
        delayMs = 0;
        lossPercent = 0;
    }

    /** The connections the link carries now. */
    public int connections() {
        return open.size() / 2;
    }

    private void accept() {
        while (!closed) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return;
            }
            if (partitioned) {
                closeQuietly(client);
                continue;
            }
            Socket upstream = new Socket();
            try {
                upstream.connect(target, 5_000);
            } catch (IOException e) {
                closeQuietly(client);
                closeQuietly(upstream);
                continue;
            }
            open.add(client);
            open.add(upstream);
            threads.execute(() -> pump(client, upstream));
            threads.execute(() -> pump(upstream, client));
        }
    }

    private void pump(Socket from, Socket to) {
        byte[] buffer = new byte[16 * 1024];
        try (InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream()) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (partitioned) {
                    break;
                }
                long wait = delayMs;
                int loss = lossPercent;
                if (loss > 0 && chance() < loss) {
                    wait += RESEND_MS;
                }
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // one end closed: the other follows
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            open.remove(from);
            open.remove(to);
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private synchronized int chance() {
        return random.nextInt(100);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // closed already
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // closed already
        }
        partition();
        threads.shutdownNow();
    }

    @Override
    public String toString() {
        return name + " :" + port() + " -> " + target;
    }
}
