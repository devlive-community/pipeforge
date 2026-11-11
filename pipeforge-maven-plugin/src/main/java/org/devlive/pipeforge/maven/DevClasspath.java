/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The dependency list of a plugin module for development mode: one absolute path per line of the
 * jars that the plugin package would ship in lib/. A server in development mode loads the module
 * from target/classes and these jars, without a plugin package.
 */
public final class DevClasspath {

    /** Where the list is written, relative to the build directory. */
    public static final String FILE = "pipeforge-plugin/classpath.txt";

    static final String HEADER = "# Runtime dependencies of the plugin for development mode; written by"
            + " pipeforge-maven-plugin:dev-classpath";

    private DevClasspath() {}

    /** The content of the list. */
    static String render(List<Path> jars) {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        for (Path jar : jars) {
            text.append(jar.toAbsolutePath().normalize()).append('\n');
        }
        return text.toString();
    }

    /**
     * Writes the list unless it already has this content, so that an unchanged build does not touch
     * the file.
     *
     * @return whether the file was written
     */
    public static boolean write(Path file, List<Path> jars) throws IOException {
        String content = render(jars);
        if (Files.isRegularFile(file)
                && Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
            return false;
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return true;
    }
}
