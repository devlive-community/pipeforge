/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import java.util.Locale;

/** Error categories: the second part of an error code, with the default {@link ErrorKind}. */
public enum ErrorCategory {
    /** Configuration: missing or invalid options, controlled native keys, undefined variables. */
    CONF(ErrorKind.FATAL),
    /** Planning: cycles, incompatible schemas, unsupported capabilities, route conflicts. */
    PLAN(ErrorKind.FATAL),
    /** Plugins: missing, incompatible or failing to load, missing drivers. */
    PLUG(ErrorKind.FATAL),
    /** Connections: refused, timed out or lost. */
    CONN(ErrorKind.TRANSIENT),
    /** Authentication and permissions. */
    AUTH(ErrorKind.FATAL),
    /** Metadata: missing databases, tables or columns, failed DDL. */
    META(ErrorKind.FATAL),
    /** Data: conversion failures, constraint violations, malformed records. */
    DATA(ErrorKind.DATA),
    /** Source runtime errors. */
    SRC(ErrorKind.FATAL),
    /** Sink runtime errors. */
    SINK(ErrorKind.FATAL),
    /** Checkpoints and state. */
    CKPT(ErrorKind.FATAL),
    /** Resources: slots, memory, disk. */
    RES(ErrorKind.TRANSIENT),
    /** Cluster: lost workers, master failover, version mismatches. */
    CLUS(ErrorKind.TRANSIENT),
    /** Internal errors; always a defect that should be reported. */
    SYS(ErrorKind.FATAL);

    private final ErrorKind defaultKind;

    ErrorCategory(ErrorKind defaultKind) {
        this.defaultKind = defaultKind;
    }

    /** The kind used when neither the registry nor the thrower overrides it. */
    public ErrorKind defaultKind() {
        return defaultKind;
    }

    /** File name of the core registry of this category, e.g. {@code errors/conf.yaml}. */
    public String registryResource() {
        return "errors/" + name().toLowerCase(Locale.ROOT) + ".yaml";
    }
}
