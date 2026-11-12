/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * pipeforge dev up, down and status with Docker stubbed; CI runs them against the real compose
 * project (the dev-env job).
 */
class DevCommandTest {

    /** docker compose ps --format json of Compose 2.21 and later: one container per line, trimmed. */
    static final String PS_LINES = String.join(
            "\n",
            "{\"ExitCode\":0,\"Health\":\"healthy\",\"Name\":\"pipeforge-dev-mysql-1\",\"Publishers\":["
                    + "{\"URL\":\"0.0.0.0\",\"TargetPort\":3306,\"PublishedPort\":33306,\"Protocol\":\"tcp\"},"
                    + "{\"URL\":\"::\",\"TargetPort\":3306,\"PublishedPort\":33306,\"Protocol\":\"tcp\"}],"
                    + "\"Service\":\"mysql\",\"State\":\"running\"}",
            "{\"ExitCode\":0,\"Health\":\"healthy\",\"Name\":\"pipeforge-dev-toxiproxy-1\",\"Publishers\":["
                    + "{\"URL\":\"0.0.0.0\",\"TargetPort\":8474,\"PublishedPort\":38474,\"Protocol\":\"tcp\"},"
                    + "{\"URL\":\"0.0.0.0\",\"TargetPort\":23306,\"PublishedPort\":43306,\"Protocol\":\"tcp\"},"
                    + "{\"URL\":\"\",\"TargetPort\":9999,\"PublishedPort\":0,\"Protocol\":\"tcp\"}],"
                    + "\"Service\":\"toxiproxy\",\"State\":\"running\"}",
            "{\"ExitCode\":0,\"Health\":\"\",\"Name\":\"pipeforge-dev-minio-init-1\",\"Publishers\":null,"
                    + "\"Service\":\"minio-init\",\"State\":\"exited\"}");

    @TempDir
    Path dir;

    Shell shell;

    Path checkout;

    String ps = PS_LINES;

    @BeforeEach
    void setUp() throws Exception {
        checkout = dir.resolve("checkout");
        shell = new Shell(checkout.resolve("connectors").resolve("mine"));
        environment(checkout.resolve("dev"));
        shell.answer = command -> {
            if (command.equals(List.of("docker", "version", "--format", "{{.Server.Version}}"))) {
                return Optional.of("29.8.1");
            }
            if (command.equals(List.of("docker", "compose", "version", "--short"))) {
                return Optional.of("5.5.1");
            }
            if (command.contains("ps")) {
                return Optional.of(ps);
            }
            return Optional.empty();
        };
    }

    void environment(Path at) throws Exception {
        java.nio.file.Files.createDirectories(at);
        java.nio.file.Files.writeString(
                at.resolve("docker-compose.yaml"), "# test\nname: pipeforge-dev\n\nservices: {}\n");
    }

    List<String> compose(Path at) {
        return List.of(
                "docker",
                "compose",
                "--project-directory",
                at.toString(),
                "-f",
                at.resolve("docker-compose.yaml").toString());
    }

    @Test
    void upStartsTheEnvironmentOfTheCheckoutAndShowsItsStatus() {
        Shell.Result result = shell.run("dev", "up", "--s3", "--timeout", "90s", "mysql");
        assertThat(result.exit()).as(result.err()).isZero();
        Path env = checkout.resolve("dev");
        assertThat(shell.commands)
                .contains(
                        append(compose(env), "--profile", "s3", "up", "-d", "--wait", "--wait-timeout", "90", "mysql"));
        assertThat(shell.launched).containsExactly(env);
        assertThat(result.out())
                .isEqualTo("Development environment: " + env + "\n"
                        + "SERVICE     STATE      HEALTH   PORTS\n"
                        + "minio-init  completed  -        -\n"
                        + "mysql       running    healthy  33306->3306\n"
                        + "toxiproxy   running    healthy  38474->8474, 43306->23306\n"
                        + "3 service(s), all healthy\n");
        assertThat(result.err()).contains("Running docker compose --project-directory");
    }

    @Test
    void statusReadsTheArrayOfOlderComposeVersionsAsJson() {
        ps = "[" + String.join(",", PS_LINES.lines().toList()) + "]";
        Shell.Result result = shell.run("dev", "status", "-o", "json");
        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out())
                .contains("\"running\" : true")
                .contains("\"healthy\" : true")
                .contains("\"service\" : \"toxiproxy\"")
                .contains("\"ports\" : [ \"38474->8474\", \"43306->23306\" ]");
        Path env = checkout.resolve("dev");
        assertThat(shell.commands).contains(append(compose(env), "--profile", "s3", "ps", "--all", "--format", "json"));
    }

    @Test
    void statusFailsWhenAServiceIsNotHealthy() {
        ps = PS_LINES.replace(
                        "\"Health\":\"healthy\",\"Name\":\"pipeforge-dev-mysql-1\"",
                        "\"Health\":\"unhealthy\",\"Name\":\"pipeforge-dev-mysql-1\"")
                // A stopped service exits with 0 too, but is not a one-off service.
                .replace(
                        "\"Service\":\"toxiproxy\",\"State\":\"running\"",
                        "\"Service\":\"toxiproxy\",\"State\":\"exited\"");
        Shell.Result result = shell.run("dev", "status");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out())
                .contains("mysql       running    unhealthy")
                .contains("toxiproxy   exited")
                .endsWith("2 of 3 service(s) not healthy: mysql, toxiproxy\n");
    }

    @Test
    void statusFailsWhenNothingRuns() {
        ps = "";
        Shell.Result result = shell.run("dev", "status");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).endsWith("Not running; start it with pipeforge dev up.\n");
        assertThat(shell.run("dev", "status", "-o", "json").out())
                .contains("\"running\" : false")
                .contains("\"services\" : [ ]");
    }

    @Test
    void downStopsTheOptionalServicesToo() {
        Shell.Result result = shell.run("dev", "down", "--volumes");
        assertThat(result.exit()).as(result.err()).isZero();
        Path env = checkout.resolve("dev");
        assertThat(shell.commands)
                .contains(append(compose(env), "--profile", "s3", "down", "--remove-orphans", "--volumes"));
        assertThat(result.out())
                .isEqualTo("Stopped the development environment in " + env + " and deleted its volumes\n");
        assertThat(shell.run("dev", "down", "-o", "json").out()).contains("\"volumesRemoved\" : false");
    }

    @Test
    void aFailingComposeIsAnError() {
        shell.launchExit = 1;
        shell.launch = "Error response from daemon: port is already allocated";
        Shell.Result result = shell.run("dev", "up");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err())
                .contains("port is already allocated")
                .contains("ERROR PF-CLUS-0004 docker compose up failed with exit code 1");
    }

    @Test
    void withoutDockerNothingIsStarted() {
        shell.answer = command -> Optional.empty();
        Shell.Result result = shell.run("dev", "up");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("ERROR PF-CLUS-0003 Docker is not available: docker version does not answer");
        assertThat(shell.launched).isEmpty();

        shell.answer = command -> command.get(1).equals("version") ? Optional.of("29.8.1") : Optional.empty();
        assertThat(shell.run("dev", "status").err()).contains("Compose v2 is not installed");

        shell.answer = command -> Optional.of("29.8.1");
        shell.launchFails = true;
        assertThat(shell.run("dev", "down").err()).contains("PF-CLUS-0003").contains("Cannot run program");
    }

    @Test
    void findsTheEnvironmentOfTheInstallationOrOfDir() throws Exception {
        Shell outside = new Shell(dir.resolve("elsewhere"));
        outside.answer = shell.answer;
        Shell.Result missing = outside.run("dev", "status");
        assertThat(missing.exit()).isEqualTo(6);
        assertThat(missing.err()).contains("PF-CONF-0008").contains("pass --dir");

        Path home = dir.resolve("pipeforge-0.1.0");
        environment(home.resolve("dev"));
        outside.variables.put("PIPEFORGE_HOME", home.toString());
        assertThat(outside.run("dev", "status").exit()).isZero();
        assertThat(outside.commands)
                .contains(append(compose(home.resolve("dev")), "--profile", "s3", "ps", "--all", "--format", "json"));

        Path other = dir.resolve("other");
        java.nio.file.Files.createDirectories(other);
        java.nio.file.Files.writeString(other.resolve("docker-compose.yaml"), "name: something-else\n");
        Shell.Result notOurs = outside.run("dev", "status", "--dir", other.toString());
        assertThat(notOurs.exit()).isEqualTo(6);
        assertThat(notOurs.err()).contains("no docker-compose.yaml of the PipeForge development environment here");
    }

    @Test
    void recognizesTheEnvironmentOfThisRepository() {
        // The marker the command looks for must stay in dev/docker-compose.yaml.
        assertThat(org.devlive.pipeforge.cli.command.DevCommand.Location.isEnvironment(Path.of("..", "dev")))
                .isTrue();
    }

    static List<String> append(List<String> command, String... more) {
        List<String> all = new java.util.ArrayList<>(command);
        all.addAll(List.of(more));
        return all;
    }
}
