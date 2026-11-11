/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.testkit;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.devlive.pipeforge.engine.api.JobHandle;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.SubmitOptions;
import org.devlive.pipeforge.engine.local.LocalEngine;
import org.opentest4j.AssertionFailedError;

/**
 * Runs test jobs on the local engine. One instance serves every test of a test run; the {@link
 * TestkitExtension} creates it and closes it at the end.
 */
public final class TestkitEngine implements AutoCloseable {

    /** How long a test job may run before the test fails. */
    public static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final LocalEngine engine = new LocalEngine();

    /** Plans and runs {@code job} and waits until it ends. */
    public TestRun run(TestJob job) {
        return run(job, SubmitOptions.defaults());
    }

    public TestRun run(TestJob job, SubmitOptions options) {
        JobHandle handle = engine.submit(job.plan(), options);
        try {
            JobResult result = handle.result().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return new TestRun(job.name(), result);
        } catch (TimeoutException e) {
            handle.cancel();
            throw new AssertionFailedError("Job " + job.name() + " did not end within " + TIMEOUT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancel();
            throw new AssertionFailedError("Interrupted while running job " + job.name(), e);
        } catch (ExecutionException e) {
            throw new AssertionFailedError("Job " + job.name() + " could not run", e.getCause());
        }
    }

    @Override
    public void close() {
        engine.close();
    }
}
