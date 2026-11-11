/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.maven;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.devlive.pipeforge.common.plugin.PluginDescriptor;

/** Shared steps of the plugin goals: read plugin.yaml, check the plugin, build the docs model. */
abstract class AbstractPluginMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /** The plugin descriptor. */
    @Parameter(property = "pipeforge.descriptor", defaultValue = "${project.basedir}/src/main/plugin/plugin.yaml")
    protected File descriptor;

    /** Skips the goal. */
    @Parameter(property = "pipeforge.skip", defaultValue = "false")
    protected boolean skip;

    /** The result of checking the plugin. */
    record Checked(PluginDescriptor descriptor, List<PluginInspector.Inspected> components, List<String> problems) {}

    protected PluginDescriptor readDescriptor() throws MojoFailureException, MojoExecutionException {
        if (!descriptor.isFile()) {
            throw new MojoFailureException("Plugin descriptor not found: " + descriptor);
        }
        try {
            PluginDescriptor.ParseResult result =
                    PluginDescriptor.parse(Files.readString(descriptor.toPath(), StandardCharsets.UTF_8));
            if (!result.isValid()) {
                throw new MojoFailureException(report(descriptor.getName(), result.problems()));
            }
            return result.descriptor();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read " + descriptor, e);
        }
    }

    protected List<DependencyIsolation.Dependency> dependencies() {
        List<DependencyIsolation.Dependency> list = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            list.add(new DependencyIsolation.Dependency(
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getVersion(),
                    artifact.getScope(),
                    artifact.getFile()));
        }
        return list;
    }

    /** Runs every check and inspects the components. */
    protected Checked check() throws MojoFailureException, MojoExecutionException {
        PluginDescriptor plugin = readDescriptor();
        List<String> problems = new ArrayList<>();
        if (!plugin.version().equals(project.getVersion())) {
            problems.add("plugin.yaml version " + plugin.version() + " differs from the project version "
                    + project.getVersion());
        }
        List<DependencyIsolation.Dependency> dependencies = dependencies();
        dependencies.stream()
                .filter(d -> d.groupId().equals(DependencyIsolation.GROUP)
                        && d.artifactId().equals("pipeforge-api"))
                .findFirst()
                .ifPresentOrElse(
                        api -> {
                            String major = api.version().split("\\.", 2)[0] + ".x";
                            if (!major.equals(plugin.apiVersion())) {
                                problems.add("plugin.yaml api-version " + plugin.apiVersion()
                                        + " does not match the pipeforge-api dependency " + api.version());
                            }
                        },
                        () -> problems.add("the plugin must depend on org.devlive.pipeforge:pipeforge-api"));
        problems.addAll(DependencyIsolation.checkScopes(dependencies));
        try {
            problems.addAll(DependencyIsolation.checkClasses(
                    Path.of(project.getBuild().getOutputDirectory()), DependencyIsolation.shipped(dependencies)));
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read the plugin classes", e);
        }
        try (URLClassLoader loader = classLoader(dependencies)) {
            PluginInspector.Result result = PluginInspector.inspect(plugin, loader);
            problems.addAll(result.problems());
            return new Checked(plugin, result.components(), problems);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot load the plugin classes", e);
        }
    }

    /** Plugin classes and shipped jars, with this Maven plugin's API classes as parent. */
    private URLClassLoader classLoader(List<DependencyIsolation.Dependency> dependencies) throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        urls.add(new File(project.getBuild().getOutputDirectory()).toURI().toURL());
        for (DependencyIsolation.Dependency dependency : DependencyIsolation.shipped(dependencies)) {
            urls.add(dependency.file().toURI().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
    }

    protected static Map<String, Object> docsModel(Checked checked) {
        return PluginDocs.model(checked.descriptor(), checked.components());
    }

    protected static String report(String subject, List<String> problems) {
        StringBuilder text = new StringBuilder(subject)
                .append(" has ")
                .append(problems.size())
                .append(" problem(s):");
        for (String problem : problems) {
            text.append(System.lineSeparator()).append("  - ").append(problem);
        }
        return text.toString();
    }

    protected void failOnProblems(Checked checked) throws MojoFailureException {
        if (!checked.problems().isEmpty()) {
            throw new MojoFailureException(
                    report("Plugin " + checked.descriptor().coordinate(), checked.problems()));
        }
    }
}
