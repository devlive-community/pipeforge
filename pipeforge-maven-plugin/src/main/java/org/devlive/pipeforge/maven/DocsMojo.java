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

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping plugin docs");
            return;
        }
        Checked checked = check();
        failOnProblems(checked);
        try {
            Path file = PluginDocs.write(docsModel(checked), outputDirectory.toPath());
            getLog().info("Wrote " + file);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write the plugin docs", e);
        }
    }
}
