/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Failure;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;

/** Runs Maven in a plugin project: its wrapper when it has one, else mvn from the PATH. */
final class Maven {

    private Maven() {}

    /**
     * The plugin project in {@code directory}: a pom.xml with src/main/plugin/plugin.yaml.
     *
     * @throws Failure {@link ErrorCode#FILE_NOT_READABLE} with exit code 6 when it is not one
     */
    static Path project(Session session, Path directory) {
        Path project =
                session.environment().workingDirectory().resolve(directory).normalize();
        if (!Files.isRegularFile(project.resolve("pom.xml"))) {
            throw JobFiles.unreadable(
                    project.resolve("pom.xml"), session.messages().get("plugin.no-project"));
        }
        if (!Files.isRegularFile(project.resolve("src/main/plugin/plugin.yaml"))) {
            throw JobFiles.unreadable(
                    project.resolve("src/main/plugin/plugin.yaml"),
                    session.messages().get("plugin.no-project"));
        }
        return project;
    }

    /** The Maven command: --maven, else the nearest mvnw of the project or a parent, else mvn. */
    static String executable(Path project, @Nullable Path maven) {
        if (maven != null) {
            return maven.toString();
        }
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String wrapper = windows ? "mvnw.cmd" : "mvnw";
        for (Path dir = project; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(wrapper))) {
                return dir.resolve(wrapper).toString();
            }
        }
        return windows ? "mvn.cmd" : "mvn";
    }

    /**
     * Runs Maven with {@code arguments} on the project's pom; its output goes to the standard error
     * as it comes, since it is progress rather than a result.
     */
    static int run(Session session, Path project, @Nullable Path maven, List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable(project, maven));
        command.add("-B");
        if (session.quiet()) {
            command.add("-q");
        }
        command.add("-f");
        command.add(project.resolve("pom.xml").toString());
        command.addAll(arguments);
        session.note(session.messages().get("plugin.running", String.join(" ", command)));
        try {
            return session.environment().launcher().launch(command, project, line -> {
                session.err().println(line);
                session.err().flush();
            });
        } catch (java.io.IOException e) {
            throw new Failure(
                    PipeForgeException.of(ErrorCode.FILE_NOT_READABLE)
                            .param("path", command.get(0))
                            .param("reason", session.messages().get("plugin.no-maven", String.valueOf(e.getMessage())))
                            .build(),
                    ExitCode.NOT_FOUND);
        }
    }
}
