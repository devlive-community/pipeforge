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
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;
import org.devlive.pipeforge.plugin.check.PluginDocs;

/**
 * Builds the plugin package {@code <artifactId>-<version>.zip} with plugin.yaml, lib/, docs/,
 * native-options/ and icon.svg, after the same checks as the validate goal. The zip is attached
 * to the project so that it is installed and deployed with the jar.
 */
@Mojo(
        name = "package",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true)
public final class PackageMojo extends AbstractPluginMojo {

    /** Directory with optional package content: native-options/ and icon.svg. */
    @Parameter(defaultValue = "${project.basedir}/src/main/plugin")
    private File pluginDirectory;

    @Parameter(defaultValue = "${project.build.directory}")
    private File buildDirectory;

    @Component
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping plugin packaging");
            return;
        }
        File jar = project.getArtifact().getFile();
        if (jar == null || !jar.isFile()) {
            throw new MojoFailureException("The plugin jar does not exist yet; run the package phase");
        }
        Checked checked = check();
        failOnProblems(checked);
        try {
            Path work = buildDirectory.toPath().resolve("pipeforge-plugin");
            Path docs = PluginDocs.write(docsModel(checked), work.resolve("docs"));
            List<Path> libs = new ArrayList<>();
            libs.add(jar.toPath());
            DependencyIsolation.shipped(dependencies())
                    .forEach(d -> libs.add(d.file().toPath()));
            Path zip = buildDirectory.toPath().resolve(project.getArtifactId() + "-" + project.getVersion() + ".zip");
            Path pluginDir = pluginDirectory.toPath();
            PluginArchive.write(
                    zip,
                    descriptor.toPath(),
                    libs,
                    List.of(docs),
                    List.of(pluginDir.resolve("native-options")),
                    pluginDir.resolve("icon.svg"));
            projectHelper.attachArtifact(project, "zip", zip.toFile());
            getLog().info("Built plugin package " + zip);
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException("Cannot build the plugin package", e);
        }
    }
}
