/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.cli;

import java.util.concurrent.Callable;
import org.devlive.pipeforge.common.error.PipeForgeException;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * A command: runs with a {@link Session} and returns its exit code. Coded errors are printed by
 * {@link Errors}; a {@link Failure} chooses the exit code, other coded errors exit with 1.
 */
public abstract class BaseCommand implements Callable<Integer> {

    @Spec
    protected CommandSpec spec;

    private Environment environment = Environment.system();

    /** Set by the factory of {@link Cli} before the command runs. */
    void environment(Environment value) {
        this.environment = value;
    }

    protected abstract ExitCode execute(Session session) throws Exception;

    @Override
    public final Integer call() {
        Root root = (Root) spec.root().userObject();
        try (Session session = new Session(root, environment)) {
            try {
                return execute(session).code();
            } catch (picocli.CommandLine.ParameterException e) {
                // A value that only the command can check: a usage error like those of the parser.
                session.err().println(session.messages().get("usage.error", e.getMessage()));
                session.err().println(session.messages().get("usage.help", spec.qualifiedName()));
                session.err().flush();
                return ExitCode.USAGE.code();
            } catch (Failure failure) {
                Errors.print(session, failure.error());
                return failure.exit().code();
            } catch (PipeForgeException e) {
                Errors.print(session, e);
                return ExitCode.ERROR.code();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ExitCode.CANCELLED.code();
            } catch (RuntimeException e) {
                Errors.internal(session, e);
                return ExitCode.ERROR.code();
            } catch (Exception e) {
                Errors.internal(session, new IllegalStateException(e.getMessage(), e));
                return ExitCode.ERROR.code();
            }
        }
    }
}
