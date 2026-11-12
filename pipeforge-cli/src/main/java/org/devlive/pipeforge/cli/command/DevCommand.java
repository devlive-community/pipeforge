/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Failure;
import org.devlive.pipeforge.cli.Output;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code pipeforge dev}: starts, stops and shows the local development environment, the docker
 * compose project in dev/ of the source checkout or of the installation.
 */
@Command(
        name = "dev",
        mixinStandardHelpOptions = true,
        subcommands = {DevCommand.Up.class, DevCommand.Down.class, DevCommand.Status.class})
public final class DevCommand implements java.util.concurrent.Callable<Integer> {

    /** The file that marks the directory of the environment. */
    static final String COMPOSE_FILE = "docker-compose.yaml";

    /** The compose project name in that file; other compose files are not ours. */
    static final String PROJECT = "name: pipeforge-dev";

    /** The profile of the optional services; down and status always include it. */
    static final String S3_PROFILE = "s3";

    /** The suffix of one-off setup services, such as minio-init: they end once they are done. */
    static final String ONE_OFF_SUFFIX = "-init";

    private static final ObjectMapper JSON = new ObjectMapper();

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return ExitCode.USAGE.code();
    }

    /** The --dir option of every dev command. */
    public static final class Location {

        @Option(names = "--dir", paramLabel = "<dir>", descriptionKey = "dev.dir")
        @Nullable
        Path directory;

        /**
         * The directory of the environment: --dir, else dev/ of the nearest checkout around the
         * working directory, else dev/ of the installation.
         *
         * @throws Failure {@link ErrorCode#FILE_NOT_READABLE} with exit code 6 when there is none
         */
        Path resolve(Session session) {
            Path working = session.environment().workingDirectory();
            if (directory != null) {
                Path dir = working.resolve(directory).normalize();
                if (!isEnvironment(dir)) {
                    throw JobFiles.unreadable(
                            dir.resolve(COMPOSE_FILE), session.messages().get("dev.not-environment"));
                }
                return dir;
            }
            List<Path> searched = new ArrayList<>();
            for (Path dir = working; dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve("dev");
                if (isEnvironment(candidate)) {
                    return candidate;
                }
            }
            searched.add(working.resolve("dev"));
            Path home = session.environment().home();
            if (home != null) {
                Path candidate = home.resolve("dev");
                if (isEnvironment(candidate)) {
                    return candidate;
                }
                searched.add(candidate);
            }
            throw JobFiles.unreadable(
                    searched.get(searched.size() - 1).resolve(COMPOSE_FILE),
                    session.messages().get("dev.not-found", searched.get(0)));
        }

        /** Whether {@code dir} has the compose file of the PipeForge environment. */
        public static boolean isEnvironment(Path dir) {
            Path file = dir.resolve(COMPOSE_FILE);
            if (!Files.isRegularFile(file)) {
                return false;
            }
            try {
                return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                        .anyMatch(line -> line.strip().equals(PROJECT));
            } catch (IOException e) {
                return false;
            }
        }
    }

    /** {@code docker compose} with the project directory and file of {@code dir}. */
    static List<String> compose(Path dir) {
        return new ArrayList<>(List.of(
                "docker",
                "compose",
                "--project-directory",
                dir.toString(),
                "-f",
                dir.resolve(COMPOSE_FILE).toString()));
    }

    /**
     * Checks that Docker and Compose v2 answer.
     *
     * @throws Failure {@link ErrorCode#DOCKER_NOT_AVAILABLE} with exit code 1 when they do not
     */
    static void requireDocker(Session session) {
        Duration timeout = Duration.ofSeconds(15);
        Optional<String> server = session.environment()
                .runner()
                .run(List.of("docker", "version", "--format", "{{.Server.Version}}"), timeout)
                .filter(v -> !v.isBlank());
        String detail = null;
        if (server.isEmpty()) {
            detail = session.messages().get("dev.no-docker");
        } else if (session.environment()
                .runner()
                .run(List.of("docker", "compose", "version", "--short"), timeout)
                .filter(v -> !v.isBlank())
                .isEmpty()) {
            detail = session.messages().get("dev.no-compose");
        }
        if (detail != null) {
            throw new Failure(
                    PipeForgeException.of(ErrorCode.DOCKER_NOT_AVAILABLE)
                            .param("detail", detail)
                            .build(),
                    ExitCode.ERROR);
        }
    }

    /**
     * Runs docker compose; its output goes to the standard error as it comes, since it is progress
     * rather than a result.
     *
     * @throws Failure {@link ErrorCode#DEV_ENVIRONMENT_FAILED} with exit code 1 when it fails
     */
    static void launch(Session session, Path dir, String action, List<String> command) throws InterruptedException {
        session.note(session.messages().get("dev.running", String.join(" ", command)));
        int exit;
        try {
            exit = session.environment().launcher().launch(command, dir, line -> {
                if (!session.quiet()) {
                    session.err().println(line);
                    session.err().flush();
                }
            });
        } catch (IOException e) {
            throw new Failure(
                    PipeForgeException.of(ErrorCode.DOCKER_NOT_AVAILABLE)
                            .param("detail", String.valueOf(e.getMessage()))
                            .build(),
                    ExitCode.ERROR);
        }
        if (exit != 0) {
            throw new Failure(
                    PipeForgeException.of(ErrorCode.DEV_ENVIRONMENT_FAILED)
                            .param("action", action)
                            .param("exit", exit)
                            .build(),
                    ExitCode.ERROR);
        }
    }

    /**
     * One service of the environment.
     *
     * @param state running, completed (a one-off -init service that ended with 0), exited, restarting…
     * @param health healthy, starting, unhealthy, or empty without a health check
     * @param ports the published ports as host->container
     * @param ok running and healthy, or completed
     */
    public record Service(String service, String state, String health, List<String> ports, boolean ok) {}

    /** The environment and its services. */
    public record StatusResult(String directory, boolean running, boolean healthy, List<Service> services) {}

    /** The services of the environment, from {@code docker compose ps}. */
    static StatusResult status(Session session, Path dir) {
        List<String> command = compose(dir);
        command.addAll(List.of("--profile", S3_PROFILE, "ps", "--all", "--format", "json"));
        String output = session.environment()
                .runner()
                .run(command, Duration.ofSeconds(30))
                .orElseThrow(() -> new Failure(
                        PipeForgeException.of(ErrorCode.DEV_ENVIRONMENT_FAILED)
                                .param("action", "ps")
                                .param("exit", "?")
                                .build(),
                        ExitCode.ERROR));
        List<Service> services = parse(output);
        boolean healthy = !services.isEmpty() && services.stream().allMatch(Service::ok);
        return new StatusResult(JobFiles.display(session, dir), !services.isEmpty(), healthy, services);
    }

    /**
     * The services in the output of {@code docker compose ps --format json}: one object per line
     * from Compose 2.21, a JSON array before.
     */
    static List<Service> parse(String output) {
        List<JsonNode> containers = new ArrayList<>();
        String text = output.strip();
        try {
            if (text.startsWith("[")) {
                JSON.readTree(text).forEach(containers::add);
            } else {
                for (String line : text.lines().toList()) {
                    if (line.strip().startsWith("{")) {
                        containers.add(JSON.readTree(line));
                    }
                }
            }
        } catch (IOException e) {
            throw new Failure(
                    PipeForgeException.of(ErrorCode.INTERNAL)
                            .param("detail", "unexpected output of docker compose ps: " + e.getMessage())
                            .build(),
                    ExitCode.ERROR);
        }
        List<Service> services = new ArrayList<>();
        for (JsonNode container : containers) {
            String state = container.path("State").asText("");
            String health = container.path("Health").asText("");
            String name = container.path("Service").asText();
            // A stopped service also exits with 0; only one-off setup services end on purpose.
            boolean completed = name.endsWith(ONE_OFF_SUFFIX)
                    && state.equals("exited")
                    && container.path("ExitCode").asInt(-1) == 0;
            Set<String> ports = new LinkedHashSet<>();
            for (JsonNode publisher : container.path("Publishers")) {
                int published = publisher.path("PublishedPort").asInt(0);
                if (published > 0) {
                    ports.add(published + "->" + publisher.path("TargetPort").asInt());
                }
            }
            boolean ok = completed || (state.equals("running") && (health.isEmpty() || health.equals("healthy")));
            services.add(new Service(name, completed ? "completed" : state, health, List.copyOf(ports), ok));
        }
        services.sort(java.util.Comparator.comparing(Service::service));
        return services;
    }

    /** The status as a table and an exit code: 1 when nothing runs or a service is not healthy. */
    static ExitCode print(Session session, StatusResult result) {
        StringBuilder text = new StringBuilder();
        text.append(session.messages().get("dev.environment", result.directory()))
                .append('\n');
        if (!result.running()) {
            text.append(session.messages().get("dev.not-running")).append('\n');
        } else {
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("SERVICE", "STATE", "HEALTH", "PORTS"));
            for (Service service : result.services()) {
                rows.add(List.of(
                        service.service(),
                        service.state(),
                        service.health().isEmpty() ? "-" : service.health(),
                        service.ports().isEmpty() ? "-" : String.join(", ", service.ports())));
            }
            text.append(Output.columns(rows));
            List<String> failing = result.services().stream()
                    .filter(s -> !s.ok())
                    .map(Service::service)
                    .toList();
            text.append(
                            failing.isEmpty()
                                    ? session.messages()
                                            .get(
                                                    "dev.healthy",
                                                    result.services().size())
                                    : session.messages()
                                            .get(
                                                    "dev.unhealthy",
                                                    failing.size(),
                                                    result.services().size(),
                                                    String.join(", ", failing)))
                    .append('\n');
        }
        session.print(result, text.toString());
        return result.healthy() ? ExitCode.SUCCESS : ExitCode.ERROR;
    }

    /** {@code pipeforge dev up}: starts the environment and waits until every service is healthy. */
    @Command(name = "up", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Up extends BaseCommand {

        @Parameters(paramLabel = "<service>", arity = "0..*", descriptionKey = "dev.up.services")
        List<String> services = new ArrayList<>();

        @Option(names = "--s3", descriptionKey = "dev.up.s3")
        boolean s3;

        @Option(
                names = "--timeout",
                paramLabel = "<duration>",
                defaultValue = "5m",
                descriptionKey = "dev.up.timeout",
                converter = RunCommand.DurationConverter.class)
        Duration timeout = Duration.ofMinutes(5);

        @Mixin
        Location location = new Location();

        @Override
        protected ExitCode execute(Session session) throws Exception {
            Path dir = location.resolve(session);
            requireDocker(session);
            List<String> command = compose(dir);
            if (s3) {
                command.addAll(List.of("--profile", S3_PROFILE));
            }
            command.addAll(
                    List.of("up", "-d", "--wait", "--wait-timeout", String.valueOf(Math.max(1, timeout.toSeconds()))));
            command.addAll(services);
            launch(session, dir, "up", command);
            return print(session, status(session, dir));
        }
    }

    /** {@code pipeforge dev down}: stops the environment, optional services included. */
    @Command(name = "down", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Down extends BaseCommand {

        @Option(
                names = {"--volumes"},
                descriptionKey = "dev.down.volumes")
        boolean volumes;

        @Mixin
        Location location = new Location();

        /** What was stopped. */
        public record DownResult(String directory, boolean volumesRemoved) {}

        @Override
        protected ExitCode execute(Session session) throws Exception {
            Path dir = location.resolve(session);
            requireDocker(session);
            List<String> command = compose(dir);
            command.addAll(List.of("--profile", S3_PROFILE, "down", "--remove-orphans"));
            if (volumes) {
                command.add("--volumes");
            }
            launch(session, dir, "down", command);
            DownResult result = new DownResult(JobFiles.display(session, dir), volumes);
            session.print(
                    result,
                    session.messages().get(volumes ? "dev.stopped-volumes" : "dev.stopped", result.directory()) + "\n");
            return ExitCode.SUCCESS;
        }
    }

    /** {@code pipeforge dev status}: shows the state and health of every service. */
    @Command(name = "status", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Status extends BaseCommand {

        @Mixin
        Location location = new Location();

        @Override
        protected ExitCode execute(Session session) {
            Path dir = location.resolve(session);
            requireDocker(session);
            return print(session, status(session, dir));
        }
    }
}
