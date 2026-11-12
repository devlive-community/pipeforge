/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Failure;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code pipeforge ui}: starts the server of the installation on the loopback interface, with the
 * same plugins as the command, waits until it answers and opens the Web UI. Ctrl-C stops it.
 */
@Command(name = "ui", mixinStandardHelpOptions = true, sortOptions = false)
public final class UiCommand extends BaseCommand {

    @Option(names = "--port", paramLabel = "<port>", defaultValue = "8080", descriptionKey = "ui.port")
    int port = 8080;

    @Option(names = "--no-browser", descriptionKey = "ui.no-browser")
    boolean noBrowser;

    @Option(names = "--server-jar", paramLabel = "<jar>", descriptionKey = "ui.server-jar")
    @Nullable
    Path serverJar;

    @Option(
            names = "--start-timeout",
            paramLabel = "<seconds>",
            defaultValue = "90",
            descriptionKey = "ui.start-timeout")
    int startTimeout = 90;

    @Override
    protected ExitCode execute(Session session) throws Exception {
        Path jar = serverJar(session);
        if (!free(port)) {
            throw new Failure(
                    PipeForgeException.of(ErrorCode.PORT_IN_USE)
                            .param("port", port)
                            .build(),
                    ExitCode.ERROR);
        }
        Path logs =
                Files.createDirectories(session.environment().stateDirectory().resolve("logs"));
        Path log = logs.resolve("ui-" + port + ".log");
        Process server = new ProcessBuilder(command(session, jar))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        Thread stop = new Thread(() -> stop(server));
        Runtime.getRuntime().addShutdownHook(stop);
        try {
            String url = "http://127.0.0.1:" + port + "/";
            session.note(session.messages().get("ui.starting", url, log));
            waitUntilReady(session, server, log);
            session.print(
                    java.util.Map.of("url", url, "log", log.toString(), "pid", server.pid()),
                    session.messages().get("ui.ready", url) + "\n");
            if (!noBrowser) {
                browse(session, url);
            }
            int exit = server.waitFor();
            session.note(session.messages().get("ui.stopped", exit));
            return ExitCode.SUCCESS;
        } catch (InterruptedException e) {
            stop(server);
            Thread.currentThread().interrupt();
            return ExitCode.SUCCESS;
        } finally {
            stop(server);
            try {
                Runtime.getRuntime().removeShutdownHook(stop);
            } catch (IllegalStateException e) {
                // Shutting down: the hook stops the server.
            }
        }
    }

    /** The java command that starts the server with the plugins of this command. */
    public List<String> command(Session session, Path jar) {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-jar");
        command.add(jar.toString());
        command.add("--server.address=127.0.0.1");
        command.add("--server.port=" + port);
        command.add("--pipeforge.plugin.paths="
                + session.pluginDirectories().stream().map(Path::toString).collect(Collectors.joining(",")));
        command.add("--pipeforge.plugin.cache=" + session.pluginCache());
        Optional<Path> dev = session.devRoot();
        if (dev.isPresent()) {
            command.add("--spring.profiles.active=dev");
            command.add("--pipeforge.plugin.dev.root=" + dev.get());
        }
        return command;
    }

    /** --server-jar, else lib/server of the installation, else with --dev the build of the checkout. */
    Path serverJar(Session session) {
        if (serverJar != null) {
            Path jar =
                    session.environment().workingDirectory().resolve(serverJar).normalize();
            if (!Files.isRegularFile(jar)) {
                throw JobFiles.unreadable(jar, session.messages().get("file.missing"));
            }
            return jar;
        }
        List<Path> searched = new ArrayList<>();
        Path home = session.environment().home();
        if (home != null) {
            searched.add(home.resolve("lib").resolve("server"));
        }
        session.devRoot()
                .ifPresent(root -> searched.add(root.resolve("pipeforge-server").resolve("target")));
        for (Path directory : searched) {
            Optional<Path> jar = find(directory);
            if (jar.isPresent()) {
                return jar.get();
            }
        }
        throw new Failure(
                PipeForgeException.of(ErrorCode.SERVER_NOT_INSTALLED)
                        .param(
                                "searched",
                                searched.isEmpty()
                                        ? "-"
                                        : searched.stream().map(Path::toString).collect(Collectors.joining(", ")))
                        .build(),
                ExitCode.NOT_FOUND);
    }

    /** The newest pipeforge-server-*-exec.jar of a directory. */
    public static Optional<Path> find(Path directory) {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "pipeforge-server-*-exec.jar")) {
            files.forEach(jars::add);
        } catch (IOException e) {
            return Optional.empty();
        }
        return jars.stream().max(java.util.Comparator.comparing(Path::toString));
    }

    static boolean free(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void waitUntilReady(Session session, Process server, Path log) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(startTimeout).toNanos();
        URI version = URI.create("http://127.0.0.1:" + port + "/api/v1/version");
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) {
                throw failed(session.messages().get("ui.exited", server.exitValue()), log);
            }
            if (answers(version)) {
                return;
            }
            Thread.sleep(200);
        }
        stop(server);
        throw failed(session.messages().get("ui.no-answer", startTimeout), log);
    }

    private static Failure failed(String reason, Path log) {
        return new Failure(
                PipeForgeException.of(ErrorCode.SERVER_START_FAILED)
                        .param("reason", reason)
                        .param("log", log.toString())
                        .build(),
                ExitCode.ERROR);
    }

    public static boolean answers(URI uri) {
        try {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(500);
            connection.setReadTimeout(2000);
            try (InputStream in = connection.getInputStream()) {
                in.readAllBytes();
                return connection.getResponseCode() == 200;
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static void browse(Session session, String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("mac")
                ? List.of("open", url)
                : os.contains("win")
                        ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                        : List.of("xdg-open", url);
        if (session.environment().runner().run(command, Duration.ofSeconds(10)).isEmpty()) {
            session.note(session.messages().get("ui.open-yourself", url));
        }
    }

    private static void stop(Process server) {
        if (!server.isAlive()) {
            return;
        }
        server.destroy();
        try {
            if (!server.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        } catch (InterruptedException e) {
            server.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
