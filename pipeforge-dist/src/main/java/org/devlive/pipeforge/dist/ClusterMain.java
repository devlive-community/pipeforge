/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.dist.config.Configuration;
import org.devlive.pipeforge.dist.config.Settings;
import org.jspecify.annotations.Nullable;

/**
 * Starts a cluster process: {@code master}, {@code worker} or {@code standalone} (a master and a
 * worker in one process). It reads {@code <home>/conf}, writes its process ID to {@code
 * <home>/run/<role>.pid} while it runs and stops on SIGTERM or Ctrl-C.
 *
 * <pre>
 * ClusterMain &lt;master|worker|standalone&gt; [--set key=value]... [--labels key=value,...]
 * </pre>
 *
 * <p>The installation directory comes from the system property {@code pipeforge.home}.
 */
public final class ClusterMain {

    static final int SETTINGS_INVALID = 2;
    static final int FAILED = 1;

    private ClusterMain() {}

    public static void main(String[] args) {
        int code = run(args, System.getenv(), home(), System.err, new CountDownLatch(1));
        if (code != 0) {
            System.exit(code);
        }
    }

    static Path home() {
        String home = System.getProperty("pipeforge.home");
        return Path.of(home == null ? "." : home).toAbsolutePath().normalize();
    }

    /** The role and the settings given on the command line. */
    record Arguments(String role, Map<String, String> overrides) {}

    static Arguments parse(String[] args) {
        if (args.length == 0 || !List.of("master", "worker", "standalone").contains(args[0])) {
            throw new IllegalArgumentException(
                    "usage: <master|worker|standalone> [--set key=value]... [--labels k=v,...]");
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ((arg.equals("--set") || arg.equals("--labels")) && i + 1 < args.length) {
                String value = args[++i];
                if (arg.equals("--labels")) {
                    overrides.put(Settings.WORKER_LABELS.key(), value);
                } else {
                    int equals = value.indexOf('=');
                    if (equals <= 0) {
                        throw new IllegalArgumentException("'--set " + value + "' is not --set key=value");
                    }
                    overrides.put(value.substring(0, equals), value.substring(equals + 1));
                }
            } else {
                throw new IllegalArgumentException("unknown argument '" + arg + "'");
            }
        }
        return new Arguments(args[0], overrides);
    }

    /**
     * Runs a process until {@code stop} is counted down or the JVM shuts down.
     *
     * @return the exit code: 0, 1 when it failed to start, 2 for wrong settings or arguments
     */
    static int run(String[] args, Map<String, String> environment, Path home, PrintStream err, CountDownLatch stop) {
        Arguments arguments;
        Configuration config;
        try {
            arguments = parse(args);
            config = Configuration.load(home.resolve("conf"), environment, arguments.overrides());
        } catch (IllegalArgumentException e) {
            err.println("ERROR " + e.getMessage());
            return SETTINGS_INVALID;
        } catch (PipeForgeException e) {
            err.println("ERROR " + e.getMessage());
            return SETTINGS_INVALID;
        }
        List<AutoCloseable> processes = new ArrayList<>();
        Path runDir = home.resolve("run");
        Path pid = runDir.resolve(arguments.role() + ".pid");
        try {
            if (!arguments.role().equals("worker")) {
                processes.add(new MasterProcess(config, home));
            }
            if (!arguments.role().equals("master")) {
                processes.add(new WorkerProcess(config, home));
            }
            Files.createDirectories(runDir);
            Files.writeString(pid, ProcessHandle.current().pid() + "\n", StandardCharsets.UTF_8);
        } catch (PipeForgeException | IOException | IllegalStateException e) {
            err.println("ERROR " + e.getMessage());
            closeAll(processes);
            return FAILED;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        // on SIGTERM the JVM exits once the hooks return: wait until the processes are closed
        Thread hook = new Thread(
                () -> {
                    stop.countDown();
                    try {
                        stopped.await(60, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                "pipeforge-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            stop.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeAll(processes);
            try {
                Files.deleteIfExists(pid);
            } catch (IOException e) {
                err.println("WARN cannot remove " + pid + ": " + e.getMessage());
            }
            stopped.countDown();
            removeHook(hook);
        }
        return 0;
    }

    private static void closeAll(List<AutoCloseable> processes) {
        for (int i = processes.size() - 1; i >= 0; i--) {
            try {
                processes.get(i).close();
            } catch (Exception e) {
                // keep stopping the others
            }
        }
    }

    private static void removeHook(@Nullable Thread hook) {
        try {
            if (hook != null) {
                Runtime.getRuntime().removeShutdownHook(hook);
            }
        } catch (IllegalStateException e) {
            // the JVM is shutting down already
        }
    }
}
