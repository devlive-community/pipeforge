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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Exports the documentation data of a plugin (options, rules, capabilities, data source
 * versions) as JSON for the documentation site: {@code docs/generated/plugins/<id>.json}.
 */
@Mojo(name = "docs", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public final class DocsMojo extends AbstractPluginMojo {

    /** Where the JSON is written. */
    @Parameter(
            property = "pipeforge.docs.outputDirectory",
            defaultValue = "${maven.multiModuleProjectDirectory}/docs/generated/plugins")
    private File outputDirectory;

    /**
     * Compares instead of writing: fails when the file in {@code outputDirectory} differs from what
     * the plugin exports now. Plugin builds run the goal this way, so that the committed documentation
     * data never goes stale.
     */
    @Parameter(property = "pipeforge.docs.check", defaultValue = "false")
    private boolean checkOnly;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping plugin docs");
            return;
        }
        Checked checked = check();
        failOnProblems(checked);
        if (checkOnly) {
            try {
                String problem = PluginDocs.stale(docsModel(checked), outputDirectory.toPath());
                if (problem != null) {
                    throw new MojoFailureException(problem + "; run the docs goal of pipeforge-maven-plugin"
                            + " without -Dpipeforge.docs.check to update it, and commit the result");
                }
                getLog().info("Plugin documentation data is up to date");
                return;
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot read the plugin docs", e);
            }
        }
        try {
            Path file = PluginDocs.write(docsModel(checked), outputDirectory.toPath());
            getLog().info("Wrote " + file);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write the plugin docs", e);
        }
    }
}
