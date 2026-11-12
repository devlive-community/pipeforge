/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Output;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.common.error.ErrorInfo;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PluginComponents;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/** {@code pipeforge validate}: checks job files without running them. */
@Command(name = "validate", mixinStandardHelpOptions = true, sortOptions = false)
public final class ValidateCommand extends BaseCommand {

    @Parameters(paramLabel = "<file>", arity = "1..*", descriptionKey = "validate.files")
    List<Path> files = new ArrayList<>();

    @Mixin
    JobOptions job = new JobOptions();

    /**
     * The validation of one file, with the fields of the REST API's validation result.
     *
     * @param job the job name, when the file could be parsed
     */
    public record Result(
            String file, @Nullable String job, boolean valid, List<ErrorInfo> errors, List<String> warnings) {}

    @Override
    protected ExitCode execute(Session session) {
        List<Result> results = new ArrayList<>();
        for (Path file : JobFiles.expand(session, files)) {
            results.add(validate(session, file));
        }
        session.print(results, table(session, results));
        return results.stream().allMatch(Result::valid) ? ExitCode.SUCCESS : ExitCode.INVALID;
    }

    private Result validate(Session session, Path file) {
        String name = JobFiles.display(session, file);
        String definition = JobFiles.read(file);
        PluginComponents[] components = new PluginComponents[1];
        try {
            JobPlan plan = job.plan(session, definition, components);
            return new Result(name, plan.name(), true, List.of(), plan.warnings());
        } catch (PipeForgeException e) {
            return new Result(name, null, false, List.of(e.toErrorInfo(session.language())), List.of());
        } finally {
            if (components[0] != null) {
                components[0].close();
            }
        }
    }

    private static String table(Session session, List<Result> results) {
        List<List<String>> rows = new ArrayList<>();
        StringBuilder details = new StringBuilder();
        for (Result result : results) {
            if (result.valid()) {
                rows.add(List.of("OK", result.file(), result.job()));
            } else {
                ErrorInfo error = result.errors().get(0);
                rows.add(List.of("FAIL", result.file(), error.code() + " " + error.message()));
            }
        }
        StringBuilder text = new StringBuilder(Output.columns(rows));
        for (Result result : results) {
            for (String warning : result.warnings()) {
                details.append("WARN  ")
                        .append(result.file())
                        .append("  ")
                        .append(warning)
                        .append('\n');
            }
            for (ErrorInfo error : result.errors()) {
                if (error.hint() != null && !error.hint().isBlank()) {
                    details.append(session.messages().get("validate.hint", result.file(), error.hint()))
                            .append('\n');
                }
            }
        }
        long valid = results.stream().filter(Result::valid).count();
        text.append(details);
        text.append(session.messages().get("validate.summary", valid, results.size() - valid))
                .append('\n');
        return text.toString();
    }
}
