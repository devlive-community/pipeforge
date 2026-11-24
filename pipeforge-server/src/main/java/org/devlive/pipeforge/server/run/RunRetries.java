/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.ErrorKind;
import org.devlive.pipeforge.common.error.ErrorRegistry;
import org.devlive.pipeforge.core.job.Delivery;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.core.plan.RetrySettings;
import org.jspecify.annotations.Nullable;

/**
 * Run-level retries ({@code env.retry}, design 34 section 3): whether a failed attempt is followed
 * by another, when, and from where.
 */
public final class RunRetries {

    private RunRetries() {}

    /**
     * What a later attempt needs to be submitted as the first was, kept with the run as JSON.
     *
     * @param scheduledMs the scheduled time of the run, in epoch milliseconds
     * @param delivery the job's delivery guarantee, {@code at-least-once} or {@code exactly-once}
     * @param timeoutMs the job's {@code env.timeout.run} in milliseconds; {@code null} for no limit
     */
    public record Resubmission(
            Map<String, String> variables,
            long scheduledMs,
            Map<String, String> plugins,
            String delivery,
            Retry retry,
            @Nullable Long timeoutMs) {

        /** A run of a job without a run timeout. */
        public Resubmission(
                Map<String, String> variables,
                long scheduledMs,
                Map<String, String> plugins,
                String delivery,
                Retry retry) {
            this(variables, scheduledMs, plugins, delivery, retry, null);
        }

        /**
         * How long the next attempt may be running, after the earlier ones ran {@code runningMs};
         * {@code null} for no limit.
         */
        public @Nullable Duration timeLeft(long runningMs) {
            return timeoutMs == null ? null : Duration.ofMillis(Math.max(0, timeoutMs - runningMs));
        }

        /** What a run of {@code plan} keeps. */
        static Resubmission of(
                JobPlan plan, Map<String, String> variables, Instant scheduled, Map<String, String> plugins) {
            RetrySettings retry = plan.retry();
            return new Resubmission(
                    variables,
                    scheduled.toEpochMilli(),
                    plugins,
                    plan.delivery().id(),
                    new Retry(
                            retry.attempts(),
                            retry.initial().toMillis(),
                            retry.max().toMillis(),
                            retry.multiplier(),
                            retry.on(),
                            retry.resume().id(),
                            retry.within() == null ? null : retry.within().toMillis()),
                    plan.timeout().run() == null ? null : plan.timeout().run().toMillis());
        }
    }

    /** The job's {@code env.retry}, as the run keeps it. */
    public record Retry(
            int attempts,
            long initialMs,
            long maxMs,
            double multiplier,
            List<String> on,
            String resume,
            @Nullable Long withinMs) {}

    /**
     * What to do after an attempt failed.
     *
     * @param retry whether another attempt follows
     * @param at when it starts
     * @param fromCheckpoint whether it starts from the run's last completed checkpoint
     * @param reason why, for the run's transition
     */
    public record Decision(boolean retry, @Nullable Instant at, boolean fromCheckpoint, String reason) {
        static Decision no(String reason) {
            return new Decision(false, null, false, reason);
        }
    }

    /**
     * Decides about attempt {@code run.attempt()} that failed with {@code error}.
     *
     * @param hasCheckpoint whether the run has a completed checkpoint to go on from
     */
    public static Decision decide(
            Runs.Run run, String error, Resubmission submission, boolean hasCheckpoint, Instant now) {
        return decide(run, error, submission, hasCheckpoint, now, 0);
    }

    /**
     * Decides about attempt {@code run.attempt()} that failed with {@code error}, after the run was
     * running {@code runningMs} in all.
     *
     * @param hasCheckpoint whether the run has a completed checkpoint to go on from
     */
    public static Decision decide(
            Runs.Run run, String error, Resubmission submission, boolean hasCheckpoint, Instant now, long runningMs) {
        Retry retry = submission.retry();
        if (run.attempt() > retry.attempts()) {
            return Decision.no(error + " (" + (retry.attempts() == 0 ? "env.retry is off" : "no retries left") + ")");
        }
        if (retry.withinMs() != null && Duration.between(run.submittedAt(), now).toMillis() > retry.withinMs()) {
            return Decision.no(error + " (past env.retry.within)");
        }
        if (!retried(error, retry.on())) {
            return Decision.no(error + " (not one of env.retry.on)");
        }
        Duration left = submission.timeLeft(runningMs);
        if (left != null && left.isZero()) {
            return Decision.no(error + " (no time left of env.timeout.run)");
        }
        boolean fromCheckpoint;
        switch (retry.resume()) {
            case "restart" -> fromCheckpoint = false;
            case "checkpoint" -> fromCheckpoint = hasCheckpoint;
            default -> {
                // auto: only where neither a checkpoint nor the start writes rows twice
                if (!submission.delivery().equals(Delivery.EXACTLY_ONCE.id())) {
                    return Decision.no(error + " (not retried: the job is delivered at least once, and going on"
                            + " from a checkpoint or starting over could write rows again; set env.retry.resume"
                            + " or retry by hand)");
                }
                fromCheckpoint = hasCheckpoint;
            }
        }
        Duration wait = new RetrySettings(
                        retry.attempts(),
                        Duration.ofMillis(retry.initialMs()),
                        Duration.ofMillis(retry.maxMs()),
                        retry.multiplier(),
                        retry.on(),
                        RetrySettings.Resume.AUTO,
                        null)
                .backoff(run.attempt());
        return new Decision(
                true,
                now.plus(wait),
                fromCheckpoint,
                error + " (retry " + run.attempt() + " of " + retry.attempts() + " in " + wait.toSeconds() + " s"
                        + (fromCheckpoint ? ", from the last checkpoint)" : ", from the start)"));
    }

    /** Whether an error with code {@code code} is one {@code on} retries. */
    static boolean retried(String code, List<String> on) {
        if (on.contains(code)) {
            return true;
        }
        java.util.regex.Matcher matcher = ErrorCode.FORMAT.matcher(code);
        if (matcher.matches() && on.contains(matcher.group(1))) {
            return true;
        }
        if (!on.contains("TRANSIENT")) {
            return false;
        }
        ErrorKind kind = ErrorRegistry.global()
                .find(code)
                .map(org.devlive.pipeforge.common.error.ErrorDefinition::kind)
                .orElse(null);
        return kind == ErrorKind.TRANSIENT;
    }
}
