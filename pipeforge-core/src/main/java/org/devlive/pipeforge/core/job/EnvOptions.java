/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.option.Option;
import org.devlive.pipeforge.api.option.OptionRule;
import org.devlive.pipeforge.api.option.Options;

/**
 * The options of the {@code env} section. Defaults for nodes are written under the node kind,
 * e.g. {@code source.fetch-size}; they are checked against the plugins while planning.
 */
public final class EnvOptions {

    /** Largest parallelism a node can have. */
    public static final int MAX_PARALLELISM = 32_768;

    public static final Option<JobMode> MODE = Options.key("mode")
            .enumType(JobMode.class)
            .noDefault()
            .withDescription("batch or streaming; derived from the sources when omitted");

    public static final Option<Integer> PARALLELISM = Options.key("parallelism")
            .intType()
            .defaultValue(1)
            .withRange(1, MAX_PARALLELISM)
            .withDescription("Parallelism of nodes that do not set their own");

    public static final Option<Delivery> DELIVERY = Options.key("delivery")
            .enumType(Delivery.class)
            .defaultValue(Delivery.AT_LEAST_ONCE)
            .withDescription("at-least-once or exactly-once; exactly-once requires every sink to commit"
                    + " in two phases or to write idempotently");

    public static final Option<String> TIMEZONE = Options.key("timezone")
            .stringType()
            .noDefault()
            .withDescription("Time zone of ${date:...} variables and of date and time functions");

    public static final Option<Map<String, String>> VARIABLES =
            Options.key("variables").mapType().noDefault().withDescription("Job variables, referred to as ${var:name}");

    public static final Option<org.devlive.pipeforge.core.dirty.DirtyPolicy.Mode> DIRTY_POLICY = Options.key(
                    "dirty.policy")
            .enumType(org.devlive.pipeforge.core.dirty.DirtyPolicy.Mode.class)
            .defaultValue(org.devlive.pipeforge.core.dirty.DirtyPolicy.Mode.FAIL)
            .withDescription("fail the job at the first bad row, or skip bad rows and count them");

    public static final Option<Long> DIRTY_MAX_ROWS = Options.key("dirty.max-rows")
            .longType()
            .noDefault()
            .withRange(0L, Long.MAX_VALUE)
            .withDescription("With dirty.policy skip: the job fails once it skipped more bad rows than this");

    public static final Option<Long> LIMIT_ROWS = Options.key("limit.rows-per-second")
            .longType()
            .noDefault()
            .withRange(1L, Long.MAX_VALUE)
            .withDescription("Rows all sources of the job may read per second together");

    public static final Option<Long> LIMIT_BYTES = Options.key("limit.bytes-per-second")
            .longType()
            .noDefault()
            .withRange(1L, Long.MAX_VALUE)
            .withDescription("Bytes, as rows take them in memory, all sources of the job may read per second together");

    public static final Option<Boolean> CHECKPOINT_ENABLED = Options.key("checkpoint.enabled")
            .booleanType()
            .defaultValue(true)
            .withDescription(
                    "Take checkpoints, on a cluster with checkpoint.dir; without them a failed run starts over");

    public static final Option<java.time.Duration> CHECKPOINT_INTERVAL = Options.key("checkpoint.interval")
            .durationType()
            .noDefault()
            .withDescription("The time from one checkpoint to the next; 1min for streaming jobs and 5min for batch jobs"
                    + " when not set");

    public static final Option<java.time.Duration> CHECKPOINT_TIMEOUT = Options.key("checkpoint.timeout")
            .durationType()
            .defaultValue(java.time.Duration.ofMinutes(10))
            .withDescription("How long a checkpoint may take before it fails");

    public static final Option<java.time.Duration> CHECKPOINT_MIN_PAUSE = Options.key("checkpoint.min-pause")
            .durationType()
            .defaultValue(java.time.Duration.ZERO)
            .withDescription("The least time from the end of one checkpoint to the start of the next");

    public static final Option<Integer> CHECKPOINT_MAX_CONCURRENT = Options.key("checkpoint.max-concurrent")
            .intType()
            .defaultValue(1)
            .withRange(1, 16)
            .withDescription("The checkpoints that may be in progress at once");

    public static final Option<Integer> CHECKPOINT_TOLERABLE_FAILURES = Options.key("checkpoint.tolerable-failures")
            .intType()
            .defaultValue(3)
            .withRange(0, Integer.MAX_VALUE)
            .withDescription("The checkpoints that may fail in a row before the run fails");

    public static final Option<Integer> CHECKPOINT_RETAINED = Options.key("checkpoint.retained")
            .intType()
            .defaultValue(3)
            .withRange(1, 1000)
            .withDescription("The completed checkpoints kept; older ones are deleted");

    public static final Option<org.devlive.pipeforge.core.plan.CheckpointSettings.Externalized>
            CHECKPOINT_EXTERNALIZED = Options.key("checkpoint.externalized")
                    .enumType(org.devlive.pipeforge.core.plan.CheckpointSettings.Externalized.class)
                    .defaultValue(
                            org.devlive.pipeforge.core.plan.CheckpointSettings.Externalized.RETAIN_ON_CANCELLATION)
                    .withDescription(
                            "Keep or delete the checkpoints of a cancelled run; a failed run always keeps them");

    public static final Option<Boolean> CHECKPOINT_ON_FINISH = Options.key("checkpoint.on-finish")
            .booleanType()
            .defaultValue(true)
            .withDescription("Record a last checkpoint, with what the sinks prepared, before a bounded run commits"
                    + " it and finishes");

    public static final Option<org.devlive.pipeforge.core.plan.RestartSettings.Strategy> RESTART_STRATEGY = Options.key(
                    "restart.strategy")
            .enumType(org.devlive.pipeforge.core.plan.RestartSettings.Strategy.class)
            .defaultValue(org.devlive.pipeforge.core.plan.RestartSettings.Strategy.FIXED_DELAY)
            .withDescription("How the run restarts after a failure that may succeed when retried: none,"
                    + " fixed-delay, exponential-backoff or failure-rate");

    public static final Option<Integer> RESTART_ATTEMPTS = Options.key("restart.attempts")
            .intType()
            .defaultValue(org.devlive.pipeforge.core.plan.RestartSettings.DEFAULT_ATTEMPTS)
            .withRange(0, Integer.MAX_VALUE)
            .withDescription("fixed-delay: the restarts in all");

    public static final Option<java.time.Duration> RESTART_DELAY = Options.key("restart.delay")
            .durationType()
            .defaultValue(org.devlive.pipeforge.core.plan.RestartSettings.DEFAULT_DELAY)
            .withDescription("fixed-delay and failure-rate: the wait before each restart");

    public static final Option<java.time.Duration> RESTART_INITIAL = Options.key("restart.initial")
            .durationType()
            .defaultValue(org.devlive.pipeforge.core.plan.RestartSettings.DEFAULT_INITIAL)
            .withDescription("exponential-backoff: the wait before the first restart");

    public static final Option<java.time.Duration> RESTART_MAX = Options.key("restart.max")
            .durationType()
            .defaultValue(org.devlive.pipeforge.core.plan.RestartSettings.DEFAULT_MAX)
            .withDescription("exponential-backoff: the longest wait");

    public static final Option<Double> RESTART_MULTIPLIER = Options.key("restart.multiplier")
            .doubleType()
            .defaultValue(org.devlive.pipeforge.core.plan.RestartSettings.DEFAULT_MULTIPLIER)
            .withRange(1.0, 1000.0)
            .withDescription("exponential-backoff: how much longer each wait is than the one before");

    public static final Option<java.time.Duration> RESTART_RESET_AFTER = Options.key("restart.reset-after")
            .durationType()
            .defaultValue(org.devlive.pipeforge.core.plan.RestartSettings.DEFAULT_RESET_AFTER)
            .withDescription("exponential-backoff: after running this long without a failure, the next wait is"
                    + " restart.initial again");

    public static final Option<Integer> RESTART_MAX_FAILURES = Options.key("restart.max-failures")
            .intType()
            .defaultValue(org.devlive.pipeforge.core.plan.RestartSettings.DEFAULT_MAX_FAILURES)
            .withRange(0, Integer.MAX_VALUE)
            .withDescription("failure-rate: the failures allowed within restart.interval");

    public static final Option<java.time.Duration> RESTART_INTERVAL = Options.key("restart.interval")
            .durationType()
            .defaultValue(org.devlive.pipeforge.core.plan.RestartSettings.DEFAULT_INTERVAL)
            .withDescription("failure-rate: the window the failures are counted in");

    /** Prefixes of node defaults: {@code source.*}, {@code transform.*} and {@code sink.*}. */
    public static final List<String> NODE_DEFAULT_PREFIXES = List.of("source", "transform", "sink");

    /** Prefixes of cluster configuration, which jobs cannot set. */
    public static final List<String> CLUSTER_PREFIXES = List.of("worker", "master", "server", "cluster");

    public static final OptionRule RULE = OptionRule.builder()
            .optional(
                    MODE,
                    PARALLELISM,
                    DELIVERY,
                    TIMEZONE,
                    VARIABLES,
                    DIRTY_POLICY,
                    DIRTY_MAX_ROWS,
                    LIMIT_ROWS,
                    LIMIT_BYTES,
                    CHECKPOINT_ENABLED,
                    CHECKPOINT_INTERVAL,
                    CHECKPOINT_TIMEOUT,
                    CHECKPOINT_MIN_PAUSE,
                    CHECKPOINT_MAX_CONCURRENT,
                    CHECKPOINT_TOLERABLE_FAILURES,
                    CHECKPOINT_RETAINED,
                    CHECKPOINT_EXTERNALIZED,
                    CHECKPOINT_ON_FINISH,
                    RESTART_STRATEGY,
                    RESTART_ATTEMPTS,
                    RESTART_DELAY,
                    RESTART_INITIAL,
                    RESTART_MAX,
                    RESTART_MULTIPLIER,
                    RESTART_RESET_AFTER,
                    RESTART_MAX_FAILURES,
                    RESTART_INTERVAL)
            .nativeNamespaces(NODE_DEFAULT_PREFIXES.toArray(String[]::new))
            .build();

    private EnvOptions() {}
}
