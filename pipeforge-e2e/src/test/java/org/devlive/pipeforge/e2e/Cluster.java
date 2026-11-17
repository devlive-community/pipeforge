/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A cluster made of the packaged distribution: a master, workers and the server, each a process in
 * its own copy of the distribution (hard links, so copies cost nothing), on free ports of this
 * host. The output of every process goes to a file whose end is shown when a step fails.
 */
final class Cluster implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();

    private final Path dist;
    private final Path dir;
    private final String token = UUID.randomUUID().toString();
    private final Map<String, Process> processes = new LinkedHashMap<>();
    private final Map<String, Integer> workerPorts = new LinkedHashMap<>();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private int masterPort;
    private int serverPort;

    Cluster(Path dist, Path dir) {
        this.dist = dist;
        this.dir = dir;
    }

    static int[] freePorts(int count) throws IOException {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            int[] ports = new int[count];
            for (int i = 0; i < count; i++) {
                ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                sockets.add(socket);
                ports[i] = socket.getLocalPort();
            }
            return ports;
        } finally {
            for (ServerSocket socket : sockets) {
                socket.close();
            }
        }
    }

    /** A copy of the distribution for one process; the scripts keep their modes. */
    Path home(String name) throws IOException {
        Path home = dir.resolve(name);
        try (Stream<Path> files = Files.walk(dist)) {
            for (Path file : files.toList()) {
                Path target = home.resolve(dist.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    try {
                        Files.createLink(target, file);
                    } catch (IOException | UnsupportedOperationException e) {
                        // another file system: copy, keeping the modes
                        Files.copy(file, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
        }
        try (Stream<Path> scripts = Files.list(home.resolve("bin"))) {
            for (Path script : scripts.toList()) {
                script.toFile().setExecutable(true);
            }
        }
        return home;
    }

    private Map<String, String> environment(String javaOpts) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        environment.put("JAVA_OPTS", javaOpts);
        environment.put("PIPEFORGE_CLUSTER_TOKEN", token);
        return environment;
    }

    private Process launch(String name, Path home, List<String> command, Map<String, String> environment)
            throws IOException {
        Files.createDirectories(dir.resolve("logs"));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(home.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("logs/" + name + ".out").toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        processes.put(name, process);
        return process;
    }

    /** Starts the master with the plugins of its home; lost workers are noticed after {@code heartbeatTimeout}. */
    Path startMaster(Duration heartbeatTimeout) throws IOException {
        Path home = home("master");
        masterPort = freePorts(1)[0];
        Map<String, String> environment = environment("-Xms128m -Xmx768m");
        environment.put("PIPEFORGE_MASTER_RPC_PORT", String.valueOf(masterPort));
        environment.put("PIPEFORGE_MASTER_HEARTBEAT_INTERVAL", "1s");
        environment.put("PIPEFORGE_MASTER_HEARTBEAT_TIMEOUT", heartbeatTimeout.toSeconds() + "s");
        launch("master", home, List.of(home.resolve("bin/pipeforge-master.sh").toString(), "run"), environment);
        return home;
    }

    void startWorker(String name, int slots, String labels) throws IOException {
        Path home = home(name);
        int[] ports = freePorts(2);
        Map<String, String> environment = environment("-Xms128m -Xmx768m");
        environment.put("PIPEFORGE_WORKER_MASTER_ADDRESS", "localhost:" + masterPort);
        environment.put("PIPEFORGE_WORKER_HOST", "localhost");
        environment.put("PIPEFORGE_WORKER_RPC_PORT", String.valueOf(ports[0]));
        environment.put("PIPEFORGE_WORKER_DATA_PORT", String.valueOf(ports[1]));
        environment.put("PIPEFORGE_WORKER_SLOTS", String.valueOf(slots));
        environment.put("PIPEFORGE_WORKER_LABELS", labels);
        launch(name, home, List.of(home.resolve("bin/pipeforge-worker.sh").toString(), "run"), environment);
        workerPorts.put(name, ports[0]);
    }

    /** Starts the server of the distribution with the master's plugins, polling the master often. */
    void startServer(Path masterHome) throws IOException {
        serverPort = freePorts(1)[0];
        Path jar;
        try (Stream<Path> jars = Files.list(masterHome.resolve("lib/server"))) {
            jar = jars.filter(p -> p.getFileName().toString().endsWith("-exec.jar"))
                    .findFirst()
                    .orElseThrow();
        }
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms128m",
                "-Xmx768m",
                "-jar",
                jar.toString(),
                "--server.address=127.0.0.1",
                "--server.port=" + serverPort,
                "--pipeforge.plugin.paths=" + masterHome.resolve("plugins"),
                "--pipeforge.master.address=localhost:" + masterPort,
                "--pipeforge.master.token=" + token,
                "--pipeforge.master.poll-interval=500ms",
                "--pipeforge.master.metrics-interval=1s",
                "--pipeforge.metastore.url=jdbc:h2:file:" + dir.resolve("metastore/pipeforge"));
        launch("server", masterHome, command, Map.of());
    }

    /** The name of the worker whose RPC address is {@code address}. */
    String workerAt(String address) {
        return workerPorts.entrySet().stream()
                .filter(e -> address.endsWith(":" + e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no worker at " + address + " among " + workerPorts));
    }

    /** Ends a process at once, as a crash or a lost host would. */
    void kill(String name) throws InterruptedException {
        Process process = processes.get(name);
        process.destroyForcibly();
        process.waitFor(30, TimeUnit.SECONDS);
    }

    JsonNode get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    JsonNode post(String path, Object body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + serverPort + path);
    }

    private JsonNode send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                request.timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
        if (response.statusCode() >= 300) {
            throw new IOException(
                    "HTTP " + response.statusCode() + " for " + request.build().uri() + ": " + body);
        }
        return body;
    }

    /**
     * Asks {@code path} until the answer satisfies {@code done}; connection errors and error answers
     * count as not yet. Fails with the last answer and the end of every process's output.
     */
    JsonNode await(String path, Predicate<JsonNode> done, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Object last = null;
        while (System.nanoTime() < deadline) {
            for (Map.Entry<String, Process> entry : processes.entrySet()) {
                if (!entry.getValue().isAlive() && entry.getKey().equals("server")) {
                    throw new AssertionError("the server ended\n" + outputs());
                }
            }
            try {
                JsonNode answer = get(path);
                if (done.test(answer)) {
                    return answer;
                }
                last = answer;
            } catch (IOException e) {
                last = e.getMessage();
            }
            Thread.sleep(500);
        }
        throw new AssertionError("waited " + timeout + " for " + path + "; last answer: " + last + "\n" + outputs());
    }

    /** The last lines of every process's output. */
    String outputs() {
        StringBuilder text = new StringBuilder();
        for (String name : processes.keySet()) {
            Path out = dir.resolve("logs/" + name + ".out");
            try {
                List<String> lines = Files.exists(out) ? Files.readAllLines(out) : List.of();
                text.append("--- ")
                        .append(name)
                        .append(" ---\n")
                        .append(String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size())))
                        .append('\n');
            } catch (IOException e) {
                text.append("--- ")
                        .append(name)
                        .append(": ")
                        .append(e.getMessage())
                        .append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public void close() throws InterruptedException {
        List<String> names = new ArrayList<>(processes.keySet());
        java.util.Collections.reverse(names);
        for (String name : names) {
            Process process = processes.get(name);
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }
}
