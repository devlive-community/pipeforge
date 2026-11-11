/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.devlive.pipeforge.api.annotation.Evolving;
import org.devlive.pipeforge.api.annotation.Internal;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.row.Row;
import org.junit.jupiter.api.Test;

/** Every public type of the API declares its stability. */
class ApiStabilityTest {

    @Test
    void everyPublicTypeIsAnnotated() throws IOException, URISyntaxException, ClassNotFoundException {
        Path root = Path.of(
                Row.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> classes;
        try (Stream<Path> files = Files.walk(root)) {
            classes = files.map(root::relativize)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".class") && !name.contains("$"))
                    .filter(name -> !name.endsWith("package-info.class"))
                    .map(name ->
                            name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .toList();
        }
        assertThat(classes).hasSizeGreaterThan(50);
        for (String name : classes) {
            Class<?> type = Class.forName(name);
            if (!Modifier.isPublic(type.getModifiers()) || type.isAnnotation()) {
                continue;
            }
            assertThat(type.isAnnotationPresent(Stable.class)
                            || type.isAnnotationPresent(Evolving.class)
                            || type.isAnnotationPresent(Internal.class))
                    .as("%s declares @Stable, @Evolving or @Internal", name)
                    .isTrue();
        }
    }
}
