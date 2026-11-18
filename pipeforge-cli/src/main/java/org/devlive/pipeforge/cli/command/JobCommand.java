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
import org.devlive.pipeforge.cli.Session;
import org.devlive.pipeforge.client.ExplainResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/** Jobs on the server: {@code job explain}. */
@Command(name = "job", mixinStandardHelpOptions = true, subcommands = JobCommand.Explain.class)
public final class JobCommand extends BaseCommand {

    /** Without a subcommand: the usage, exit code 2. */
    @Override
    protected ExitCode execute(Session session) {
        spec.commandLine().usage(session.err());
        return ExitCode.USAGE;
    }

    /**
     * The plan of a job as the server makes it, with the server's plugins and settings: what the
     * cluster would run. {@code explain} plans with the plugins of this command instead.
     */
    @Command(name = "explain", mixinStandardHelpOptions = true, sortOptions = false)
    public static final class Explain extends BaseCommand {

        @Parameters(paramLabel = "<file>", descriptionKey = "job.explain.file")
        Path file;

        @Mixin
        JobOptions job = new JobOptions();

        @Override
        protected ExitCode execute(Session session) {
            Path path = JobFiles.expand(session, List.of(file)).get(0);
            ExplainResult plan = session.client().explain(JobFiles.read(path), job.variables, job.scheduledTime);
            session.print(plan, plan.text().endsWith("\n") ? plan.text() : plan.text() + "\n");
            return ExitCode.SUCCESS;
        }
    }
}
