/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Output;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.plugin.PluginRepository;
import picocli.CommandLine.Command;

/** {@code pipeforge doctor}: checks what the command needs on this machine. */
@Command(name = "doctor", mixinStandardHelpOptions = true)
public final class DoctorCommand extends BaseCommand {

    /** The outcome of a check. */
    public enum Status {
        OK,
        WARN,
        FAIL
    }

    /** One check: what was checked, how it went and what was found. */
    public record Check(String check, Status status, String detail) {}

    @Override
    protected ExitCode execute(Session session) {
        List<Check> checks = new ArrayList<>();
        checks.add(java(session));
        checks.add(installation(session));
        checks.addAll(plugins(session));
        checks.add(server(session));
        checks.add(docker(session));
        checks.add(encoding(session));
        List<List<String>> rows = new ArrayList<>();
        for (Check check : checks) {
            rows.add(List.of(label(session, check.status()), check.check(), check.detail()));
        }
        session.print(Map.of("checks", checks), Output.columns(rows));
        return checks.stream().anyMatch(c -> c.status() == Status.FAIL) ? ExitCode.ERROR : ExitCode.SUCCESS;
    }

    private static String label(Session session, Status status) {
        if (!session.color()) {
            return status.name();
        }
        String colour =
                switch (status) {
                    case OK -> "32";
                    case WARN -> "33";
                    case FAIL -> "31";
                };
        return "\u001b[" + colour + "m" + status.name() + "\u001b[0m";
    }

    private static Check java(Session session) {
        Runtime.Version version = Runtime.version();
        String detail = version + " (" + session.environment().properties().getOrDefault("java.vendor", "?") + ")";
        return version.feature() >= 21
                ? new Check("java", Status.OK, detail)
                : new Check("java", Status.FAIL, session.messages().get("doctor.java-old", detail));
    }

    private static Check installation(Session session) {
        Path home = session.environment().home();
        return home == null
                ? new Check("home", Status.WARN, session.messages().get("doctor.no-home"))
                : new Check("home", Status.OK, home.toString());
    }

    private static List<Check> plugins(Session session) {
        List<Check> checks = new ArrayList<>();
        PluginRepository.Scan scan = session.scan();
        for (PluginRepository.Directory directory : scan.directories()) {
            checks.add(
                    directory.exists()
                            ? new Check(
                                    "plugins",
                                    directory.plugins() > 0 ? Status.OK : Status.WARN,
                                    session.messages().get("doctor.plugins", directory.plugins(), directory.path()))
                            : new Check(
                                    "plugins",
                                    Status.WARN,
                                    session.messages().get("doctor.no-directory", directory.path())));
        }
        for (Map.Entry<String, String> entry : session.plugins().unavailable().entrySet()) {
            checks.add(new Check("plugins", Status.WARN, entry.getKey() + ": " + entry.getValue()));
        }
        if (scan.plugins().isEmpty()) {
            checks.add(new Check("plugins", Status.FAIL, session.messages().get("doctor.no-plugins")));
        }
        return checks;
    }

    private static Check server(Session session) {
        Path home = session.environment().home();
        Optional<Path> jar = home == null
                ? Optional.empty()
                : UiCommand.find(home.resolve("lib").resolve("server"));
        if (jar.isEmpty() && session.devRoot().isPresent()) {
            jar = UiCommand.find(
                    session.devRoot().get().resolve("pipeforge-server").resolve("target"));
        }
        return jar.map(p -> new Check("server", Status.OK, p.toString()))
                .orElseGet(() ->
                        new Check("server", Status.WARN, session.messages().get("doctor.no-server")));
    }

    private static Check docker(Session session) {
        Optional<String> version = session.environment()
                .runner()
                .run(List.of("docker", "version", "--format", "{{.Server.Version}}"), Duration.ofSeconds(5));
        return version.filter(v -> !v.isBlank())
                .map(v -> new Check("docker", Status.OK, v))
                .orElseGet(() ->
                        new Check("docker", Status.WARN, session.messages().get("doctor.no-docker")));
    }

    private static Check encoding(Session session) {
        String encoding = session.environment()
                .properties()
                .getOrDefault(
                        "stdout.encoding", session.environment().properties().getOrDefault("file.encoding", "?"));
        return encoding.toUpperCase(Locale.ROOT).replace("-", "").equals("UTF8")
                ? new Check("encoding", Status.OK, encoding)
                : new Check("encoding", Status.WARN, session.messages().get("doctor.encoding", encoding));
    }
}
