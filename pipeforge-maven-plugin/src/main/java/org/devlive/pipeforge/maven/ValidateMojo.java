/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Checks a plugin: plugin.yaml is valid and matches the project and API versions, dependencies
 * are isolated (pipeforge-api provided, no engine modules, no PipeForge classes, no duplicate
 * classes) and every declared component loads and reports its identifier.
 */
@Mojo(
        name = "validate",
        defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true)
public final class ValidateMojo extends AbstractPluginMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping plugin validation");
            return;
        }
        Checked checked = check();
        failOnProblems(checked);
        getLog().info("Plugin " + checked.descriptor().coordinate() + " is valid ("
                + checked.components().size() + " component(s))");
    }
}
