/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.devlive.pipeforge.cli.command.UiCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code pipeforge ui} with the real server jar of the build. */
class UiCommandTest {

    @TempDir
    Path dir;

    static Path serverJar() {
        Path directory = Path.of(System.getProperty("pipeforge.server", "target/test-server"));
        return UiCommandTestSupport.find(directory);
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void startsTheServerWithThePluginsAndStopsIt() throws Exception {
        Shell shell = new Shell(dir.resolve("work"));
        int port = freePort();
        AtomicReference<Shell.Result> result = new AtomicReference<>();
        Thread command = new Thread(() -> result.set(shell.run(
                "ui",
                "--no-browser",
                "--port",
                String.valueOf(port),
                "--server-jar",
                serverJar().toString())));
        command.start();
        HttpClient http = HttpClient.newHttpClient();
        URI plugins = URI.create("http://127.0.0.1:" + port + "/api/v1/plugins");
        String body = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (body == null && System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response =
                        http.send(HttpRequest.newBuilder(plugins).build(), HttpResponse.BodyHandlers.ofString());
                body = response.statusCode() == 200 ? response.body() : null;
            } catch (IOException e) {
                Thread.sleep(250);
            }
        }
        assertThat(body).as("the server answers").isNotNull();
        assertThat(body)
                .contains("\"id\":\"fake\"")
                .contains("\"id\":\"console\"")
                .contains("\"id\":\"blackhole\"");
        // A second server on the same port is refused before it starts.
        Shell.Result again = new Shell(dir.resolve("again"))
                .run(
                        "ui",
                        "--no-browser",
                        "--port",
                        String.valueOf(port),
                        "--server-jar",
                        serverJar().toString());
        assertThat(again.exit()).isEqualTo(1);
        assertThat(again.err()).startsWith("ERROR PF-RES-0006 Port " + port + " is already in use");

        command.interrupt();
        command.join(Duration.ofSeconds(30).toMillis());
        assertThat(command.isAlive()).isFalse();
        assertThat(result.get().exit()).isZero();
        assertThat(result.get().out())
                .isEqualTo("The Web UI is at http://127.0.0.1:" + port + "/; press Ctrl-C to stop the server.\n");
        assertThat(result.get().err()).contains("Starting the server for http://127.0.0.1:" + port);
        assertThat(UiCommandTestSupport.answers(port)).isFalse();
        assertThat(dir.resolve("work/home/.pipeforge/logs/ui-" + port + ".log")).exists();
    }

    @Test
    void aServerThatDoesNotStartIsReportedWithItsLog() throws Exception {
        Shell shell = new Shell(dir.resolve("work"));
        Path broken = shell.file("broken.jar", "not a jar");
        Shell.Result result = shell.run(
                "ui", "--no-browser", "--port", String.valueOf(freePort()), "--server-jar", broken.toString());
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err())
                .contains("ERROR PF-CLUS-0002 The server did not start: the process ended with exit code 1 (log: ");

        Shell.Result missing = shell.run("ui", "--server-jar", "nothing.jar");
        assertThat(missing.exit()).isEqualTo(6);
        assertThat(missing.err()).startsWith("ERROR PF-CONF-0008");
    }

    @Test
    void withoutAnInstallationTheServerIsNotFound() throws Exception {
        Shell shell = new Shell(dir.resolve("work"));
        shell.properties.put("pipeforge.home", dir.resolve("install").toString());
        Shell.Result result = shell.run("ui", "--no-browser");
        assertThat(result.exit()).isEqualTo(6);
        assertThat(shell.normalize(result.err()))
                .startsWith("ERROR PF-CONF-0009 The server is not installed: no server jar in "
                        + dir.resolve("install/lib/server"));

        Path lib = Files.createDirectories(dir.resolve("install/lib/server"));
        Files.copy(serverJar(), lib.resolve(serverJar().getFileName()));
        assertThat(UiCommand.find(lib)).contains(lib.resolve(serverJar().getFileName()));
    }

    @Test
    void theServerGetsThePluginsAndTheDevelopmentMode() throws Exception {
        Shell shell = new Shell(dir.resolve("checkout/pipeforge-server"));
        Files.writeString(dir.resolve("checkout/pom.xml"), "<project/>");
        Files.writeString(dir.resolve("checkout/pipeforge-server/pom.xml"), "<project/>");
        java.util.List<String> command =
                UiCommandTestSupport.command(shell, "--dev", "--plugin-path", "a", "--plugin-path", "b");
        assertThat(command)
                .contains("-jar", "--server.address=127.0.0.1", "--server.port=8080")
                .contains("--pipeforge.plugin.paths=" + dir.resolve("checkout/pipeforge-server/a") + ","
                        + dir.resolve("checkout/pipeforge-server/b"))
                .contains("--spring.profiles.active=dev", "--pipeforge.plugin.dev.root=" + dir.resolve("checkout"));
    }
}
