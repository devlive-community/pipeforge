/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e.chaos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorageFactory;

/**
 * The jar of the faulty checkpoint storage, made from the compiled test classes, for the {@code
 * lib} directory of a process's copy of the distribution: the storage factories are found on the
 * class path.
 */
public final class ChaosJar {

    static final List<Class<?>> CLASSES = List.of(
            FaultyCheckpointStorageFactory.class,
            FaultyCheckpointStorage.class,
            StorageControl.class,
            StorageControl.Mode.class);

    private ChaosJar() {}

    /**
     * The class files of {@link #CLASSES} with their nested and synthetic classes, such as the one
     * the compiler makes for a switch over an enum: {@code Outer$1.class} next to {@code
     * Outer.class}.
     */
    static java.util.SortedSet<String> classFiles() throws IOException {
        java.util.SortedSet<String> names = new java.util.TreeSet<>();
        for (Class<?> type : CLASSES) {
            String name = type.getName().replace('.', '/') + ".class";
            java.net.URL url = type.getClassLoader().getResource(name);
            if (url == null) {
                throw new IOException("the class file of " + type.getName() + " is not on the class path");
            }
            names.add(name);
            if (!url.getProtocol().equals("file")) {
                continue;
            }
            Path file;
            try {
                file = Path.of(url.toURI());
            } catch (java.net.URISyntaxException e) {
                throw new IOException(e);
            }
            String prefix = type.getSimpleName() + "$";
            String folder = name.substring(0, name.lastIndexOf('/') + 1);
            try (var siblings = Files.list(file.getParent())) {
                siblings.map(p -> p.getFileName().toString())
                        .filter(n -> n.startsWith(prefix) && n.endsWith(".class"))
                        .forEach(n -> names.add(folder + n));
            }
        }
        return names;
    }

    /** Writes the jar to {@code file}. */
    public static Path write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
                JarOutputStream jar = new JarOutputStream(out, new Manifest())) {
            for (String name : classFiles()) {
                try (InputStream in = ChaosJar.class.getClassLoader().getResourceAsStream(name)) {
                    if (in == null) {
                        throw new IOException("the class file " + name + " is not on the class path");
                    }
                    jar.putNextEntry(new JarEntry(name));
                    in.transferTo(jar);
                    jar.closeEntry();
                }
            }
            jar.putNextEntry(new JarEntry("META-INF/services/" + CheckpointStorageFactory.class.getName()));
            jar.write((FaultyCheckpointStorageFactory.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }
}
