/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import java.nio.file.Path;
import java.util.List;
import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Failure;
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.core.explain.ExplainDocument;
import org.devlive.pipeforge.core.explain.PlanExplainer;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.PluginComponents;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/** {@code pipeforge explain}: the plan of a job, as the server's explain shows it. */
@Command(name = "explain", mixinStandardHelpOptions = true, sortOptions = false)
public final class ExplainCommand extends BaseCommand {

    @Parameters(paramLabel = "<file>", descriptionKey = "explain.file")
    Path file;

    @Mixin
    JobOptions job = new JobOptions();

    /** The plan as a document and as text, as in the REST API. */
    public record Result(ExplainDocument plan, String text) {}

    @Override
    protected ExitCode execute(Session session) {
        Path path = JobFiles.expand(session, List.of(file)).get(0);
        PluginComponents[] components = new PluginComponents[1];
        try {
            JobPlan plan = job.plan(session, JobFiles.read(path), components);
            String text = PlanExplainer.text(plan);
            session.print(new Result(PlanExplainer.document(plan), text), text.endsWith("\n") ? text : text + "\n");
            return ExitCode.SUCCESS;
        } catch (PipeForgeException e) {
            throw new Failure(e, ExitCode.INVALID);
        } finally {
            if (components[0] != null) {
                components[0].close();
            }
        }
    }
}
