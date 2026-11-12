/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli.command;

import org.devlive.pipeforge.cli.BaseCommand;
import org.devlive.pipeforge.cli.ExitCode;
import org.devlive.pipeforge.cli.Session;
import picocli.AutoComplete;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** {@code pipeforge completion}: the completion script of a shell. */
@Command(name = "completion", mixinStandardHelpOptions = true)
public final class CompletionCommand extends BaseCommand {

    /** The shells with a completion script. */
    public enum Shell {
        BASH,
        ZSH
    }

    @Parameters(paramLabel = "<shell>", descriptionKey = "completion.shell")
    Shell shell;

    @Override
    protected ExitCode execute(Session session) {
        String script = AutoComplete.bash("pipeforge", spec.root().commandLine());
        if (shell == Shell.ZSH) {
            // The bash script runs in zsh through its bash completion layer.
            script = "autoload -U +X compinit && compinit\nautoload -U +X bashcompinit && bashcompinit\n" + script;
        }
        session.out().print(script);
        session.out().flush();
        return ExitCode.SUCCESS;
    }
}
