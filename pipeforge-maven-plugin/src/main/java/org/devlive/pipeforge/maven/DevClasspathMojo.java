/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Writes {@code target/pipeforge-plugin/classpath.txt}, the jars the plugin ships, so that a server
 * started in development mode can load the module from its build output. Runs before compilation,
 * so {@code mvn compile} is enough; it only has to run again when the dependencies change.
 */
@Mojo(
        name = "dev-classpath",
        defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true)
public final class DevClasspathMojo extends AbstractPluginMojo {

    @Parameter(defaultValue = "${project.build.directory}")
    private File buildDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping the development class path");
            return;
        }
        List<Path> jars = DependencyIsolation.shipped(dependencies()).stream()
                .map(d -> d.file().toPath())
                .toList();
        Path file = buildDirectory.toPath().resolve(DevClasspath.FILE);
        try {
            if (DevClasspath.write(file, jars)) {
                getLog().info("Wrote the development class path " + file + " (" + jars.size() + " jar(s))");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write " + file, e);
        }
    }
}
