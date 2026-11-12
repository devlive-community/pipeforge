/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Failure;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/** Job files given on the command line: files as they are, directories by their YAML and JSON files. */
final class JobFiles {

    private JobFiles() {}

    /**
     * The job files of the arguments, in order; a directory contributes its {@code .yaml}, {@code
     * .yml} and {@code .json} files, recursively and sorted.
     *
     * @throws Failure {@link ErrorCode#FILE_NOT_READABLE} with exit code 6 for a path that does not exist
     */
    static List<Path> expand(Session session, List<Path> arguments) {
        List<Path> files = new ArrayList<>();
        for (Path argument : arguments) {
            Path path =
                    session.environment().workingDirectory().resolve(argument).normalize();
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile)
                            .filter(JobFiles::isJob)
                            .sorted()
                            .forEach(files::add);
                } catch (IOException e) {
                    throw unreadable(path, e.getMessage());
                }
            } else if (Files.exists(path)) {
                files.add(path);
            } else {
                throw unreadable(path, session.messages().get("file.missing"));
            }
        }
        return files;
    }

    static boolean isJob(Path file) {
        String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw unreadable(file, String.valueOf(e.getMessage()));
        }
    }

    static Failure unreadable(Path path, String reason) {
        return new Failure(
                PipeForgeException.of(ErrorCode.FILE_NOT_READABLE)
                        .param("path", path.toString())
                        .param("reason", reason)
                        .build(),
                ExitCode.NOT_FOUND);
    }

    /** A path as the user would like to read it: relative to the working directory when inside it. */
    static String display(Session session, Path file) {
        Path working = session.environment().workingDirectory();
        return file.startsWith(working) ? working.relativize(file).toString() : file.toString();
    }
}
