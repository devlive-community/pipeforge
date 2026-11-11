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

    /** Prefixes of node defaults: {@code source.*}, {@code transform.*} and {@code sink.*}. */
    public static final List<String> NODE_DEFAULT_PREFIXES = List.of("source", "transform", "sink");

    /** Prefixes of cluster configuration, which jobs cannot set. */
    public static final List<String> CLUSTER_PREFIXES = List.of("worker", "master", "server", "cluster");

    public static final OptionRule RULE = OptionRule.builder()
            .optional(MODE, PARALLELISM, DELIVERY, TIMEZONE, VARIABLES)
            .nativeNamespaces(NODE_DEFAULT_PREFIXES.toArray(String[]::new))
            .build();

    private EnvOptions() {}
}
