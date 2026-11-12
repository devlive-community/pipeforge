/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Output;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.plugin.check.PackageCheck;
import org.devlive.pipeforge.plugin.check.PluginInspector;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code pipeforge plugin}: checks, tests and documents plugins while they are developed. */
@Command(
        name = "plugin",
        mixinStandardHelpOptions = true,
        subcommands = {PluginCommand.Validate.class, PluginCommand.Test.class, PluginCommand.Docs.class})
public final class PluginCommand implements java.util.concurrent.Callable<Integer> {

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return ExitCode.USAGE.code();
    }

    /** {@code pipeforge plugin validate}: checks built packages the way the engine uses them. */
    @Command(name = "validate", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Validate extends BaseCommand {

        @Parameters(paramLabel = "<package>", arity = "1..*", descriptionKey = "plugin.validate.packages")
        List<Path> packages = new ArrayList<>();

        /** One component that loaded. */
        public record Component(String type, String identifier, List<String> capabilities) {}

        /** The check of one package. */
        public record ValidateResult(
                String file,
                @Nullable String plugin,
                boolean passed,
                List<PackageCheck.Check> checks,
                List<Component> components) {}

        @Override
        protected ExitCode execute(Session session) throws IOException {
            List<ValidateResult> results = new ArrayList<>();
            for (Path argument : packages) {
                Path path = session.environment()
                        .workingDirectory()
                        .resolve(argument)
                        .normalize();
                if (!Files.exists(path)) {
                    throw JobFiles.unreadable(path, session.messages().get("file.missing"));
                }
                PackageCheck.Result result = PackageCheck.check(
                        path, session.pluginCache().resolveSibling("validate"), PluginCommand.class.getClassLoader());
                List<Component> components = new ArrayList<>();
                for (PluginInspector.Inspected inspected : result.components()) {
                    components.add(new Component(
                            inspected.component().type().id(),
                            inspected.component().identifier(),
                            inspected.capabilities()));
                }
                results.add(new ValidateResult(
                        JobFiles.display(session, path),
                        result.plugin(),
                        result.passed(),
                        result.checks(),
                        components));
            }
            session.print(results, table(session, results));
            return results.stream().allMatch(ValidateResult::passed) ? ExitCode.SUCCESS : ExitCode.INVALID;
        }

        private static String table(Session session, List<ValidateResult> results) {
            StringBuilder text = new StringBuilder();
            for (ValidateResult result : results) {
                text.append(result.plugin() == null ? "?" : result.plugin())
                        .append("  ")
                        .append(result.file())
                        .append('\n');
                List<List<String>> rows = new ArrayList<>();
                for (PackageCheck.Check check : result.checks()) {
                    if (check.passed()) {
                        rows.add(List.of("  OK", check.name(), ""));
                    }
                    for (String problem : check.problems()) {
                        rows.add(List.of("  FAIL", check.name(), problem));
                    }
                }
                text.append(Output.columns(rows));
                for (Component component : result.components()) {
                    text.append("  ")
                            .append(session.messages()
                                    .get(
                                            "plugin.component",
                                            component.type(),
                                            component.identifier(),
                                            component.capabilities().isEmpty()
                                                    ? "-"
                                                    : String.join(", ", component.capabilities())))
                            .append('\n');
                }
            }
            long passed = results.stream().filter(ValidateResult::passed).count();
            text.append(session.messages().get("plugin.validate.summary", passed, results.size() - passed))
                    .append('\n');
            return text.toString();
        }
    }

    /** {@code pipeforge plugin test}: runs the tests of a plugin project, the testkit contract among them. */
    @Command(name = "test", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Test extends BaseCommand {

        @Parameters(paramLabel = "<project>", defaultValue = ".", descriptionKey = "plugin.project")
        Path project = Path.of(".");

        @Option(names = "--groups", paramLabel = "<group>", split = ",", descriptionKey = "plugin.test.groups")
        List<String> groups = new ArrayList<>();

        @Option(names = "--test", paramLabel = "<pattern>", descriptionKey = "plugin.test.test")
        @Nullable
        String test;

        @Option(names = "--maven", paramLabel = "<command>", descriptionKey = "plugin.maven")
        @Nullable
        Path maven;

        /** Counts of the Surefire reports. */
        public record TestResult(String project, boolean passed, int tests, int failures, int errors, int skipped) {}

        @Override
        protected ExitCode execute(Session session) throws Exception {
            Path directory = Maven.project(session, project);
            List<String> arguments = new ArrayList<>(List.of("test"));
            if (!groups.isEmpty()) {
                arguments.add("-Dpipeforge.testkit.groups=" + String.join(",", groups));
            }
            if (test != null) {
                arguments.add("-Dtest=" + test);
                arguments.add("-Dsurefire.failIfNoSpecifiedTests=false");
            }
            long started = System.currentTimeMillis();
            int exit = Maven.run(session, directory, maven, arguments);
            int[] counts = counts(directory.resolve("target/surefire-reports"), started);
            TestResult result = new TestResult(
                    JobFiles.display(session, directory), exit == 0, counts[0], counts[1], counts[2], counts[3]);
            session.print(
                    result,
                    session.messages()
                                    .get(
                                            exit == 0 ? "plugin.test.passed" : "plugin.test.failed",
                                            result.tests(),
                                            result.failures(),
                                            result.errors(),
                                            result.skipped())
                            + "\n");
            return exit == 0 ? ExitCode.SUCCESS : ExitCode.RUN_FAILED;
        }

        /**
         * Tests, failures, errors and skipped tests of the Surefire XML reports written since {@code
         * since}; older reports belong to earlier builds.
         */
        static int[] counts(Path reports, long since) throws Exception {
            int[] counts = new int[4];
            if (!Files.isDirectory(reports)) {
                return counts;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(reports, "TEST-*.xml")) {
                for (Path file : files) {
                    if (Files.getLastModifiedTime(file).toMillis() + 1000 < since) {
                        continue;
                    }
                    Element suite =
                            factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                    String[] names = {"tests", "failures", "errors", "skipped"};
                    for (int i = 0; i < names.length; i++) {
                        String value = suite.getAttribute(names[i]);
                        counts[i] += value.isBlank() ? 0 : Integer.parseInt(value);
                    }
                }
            }
            return counts;
        }
    }

    /** {@code pipeforge plugin docs}: writes or checks the documentation data of a plugin project. */
    @Command(name = "docs", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Docs extends BaseCommand {

        private static final ObjectMapper JSON = new ObjectMapper();

        @Parameters(paramLabel = "<project>", defaultValue = ".", descriptionKey = "plugin.project")
        Path project = Path.of(".");

        @Option(names = "--output-dir", paramLabel = "<dir>", descriptionKey = "plugin.docs.output-dir")
        @Nullable
        Path outputDirectory;

        @Option(names = "--check", descriptionKey = "plugin.docs.check")
        boolean check;

        @Option(names = "--maven", paramLabel = "<command>", descriptionKey = "plugin.maven")
        @Nullable
        Path maven;

        /** The documentation data and the options of every component. */
        public record DocsResult(String file, Map<String, List<String>> options) {}

        @Override
        protected ExitCode execute(Session session) throws Exception {
            Path directory = Maven.project(session, project);
            Path output = outputDirectory == null
                    ? directory.resolve("src/generated/plugin-docs")
                    : session.environment()
                            .workingDirectory()
                            .resolve(outputDirectory)
                            .normalize();
            List<String> arguments =
                    new ArrayList<>(List.of("compile", "pipeforge:docs", "-Dpipeforge.docs.outputDirectory=" + output));
            if (check) {
                arguments.add("-Dpipeforge.docs.check=true");
            }
            int exit = Maven.run(session, directory, maven, arguments);
            if (exit != 0) {
                session.note(session.messages().get(check ? "plugin.docs.stale" : "plugin.docs.failed"));
                return check ? ExitCode.INVALID : ExitCode.RUN_FAILED;
            }
            Path file = output.resolve(pluginId(directory) + ".json");
            Map<String, List<String>> options = new LinkedHashMap<>();
            JsonNode docs = JSON.readTree(file.toFile());
            for (JsonNode component : docs.path("components")) {
                List<String> keys = new ArrayList<>();
                component
                        .path("options")
                        .forEach(option -> keys.add(option.path("key").asText()));
                options.put(
                        component.path("type").asText() + " "
                                + component.path("identifier").asText(),
                        keys);
            }
            List<List<String>> rows = new ArrayList<>();
            options.forEach(
                    (component, keys) -> rows.add(List.of(component, keys.isEmpty() ? "-" : String.join(", ", keys))));
            session.print(
                    new DocsResult(JobFiles.display(session, file), options),
                    session.messages()
                                    .get(
                                            check ? "plugin.docs.current" : "plugin.docs.written",
                                            JobFiles.display(session, file))
                            + "\n" + Output.columns(rows));
            return ExitCode.SUCCESS;
        }

        /** The id in the project's plugin.yaml. */
        private static String pluginId(Path project) throws IOException {
            String text = Files.readString(
                    project.resolve("src/main/plugin/plugin.yaml"), java.nio.charset.StandardCharsets.UTF_8);
            return org.devlive.pipeforge.common.plugin.PluginDescriptor.parse(text)
                    .orElseThrow()
                    .id();
        }
    }
}
