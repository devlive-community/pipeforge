/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stable error code such as {@code PF-CONF-0001}. A code never changes its meaning and is never
 * reused. Every code is registered with localized message and hint texts (see {@link
 * ErrorRegistry}); the parameters declared here must match the placeholders of those texts.
 *
 * <p>Core codes are constants of this class. Plugins define their own codes with {@link
 * #define(String, String, String...)} and register them in {@code META-INF/pipeforge/errors.yaml};
 * their number range carries the plugin id, e.g. {@code PF-SINK-K17-0001}.
 */
public final class ErrorCode {

    /** {@code PF-<CATEGORY>-[<PLUGIN>-]<NNNN>}. */
    public static final Pattern FORMAT = Pattern.compile("PF-([A-Z]+)-(?:([A-Z0-9]+)-)?(\\d{4})");

    private static final Pattern NAME_FORMAT = Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");

    // Configuration
    public static final ErrorCode OPTION_REQUIRED = define("PF-CONF-0001", "OPTION_REQUIRED", "option", "node");
    public static final ErrorCode OPTION_INVALID =
            define("PF-CONF-0002", "OPTION_INVALID", "option", "node", "value", "reason");
    public static final ErrorCode NATIVE_KEY_CONTROLLED =
            define("PF-CONF-0003", "NATIVE_KEY_CONTROLLED", "key", "node", "reason");
    public static final ErrorCode VARIABLE_UNDEFINED = define("PF-CONF-0004", "VARIABLE_UNDEFINED", "variable");
    public static final ErrorCode SECRET_NOT_FOUND = define("PF-CONF-0005", "SECRET_NOT_FOUND", "secret");
    public static final ErrorCode JOB_INVALID = define("PF-CONF-0006", "JOB_INVALID", "location", "reason");
    public static final ErrorCode REQUEST_INVALID = define("PF-CONF-0007", "REQUEST_INVALID", "reason");
    public static final ErrorCode FILE_NOT_READABLE = define("PF-CONF-0008", "FILE_NOT_READABLE", "path", "reason");
    public static final ErrorCode SERVER_NOT_INSTALLED = define("PF-CONF-0009", "SERVER_NOT_INSTALLED", "searched");

    // Planning
    public static final ErrorCode DAG_CYCLE = define("PF-PLAN-0001", "DAG_CYCLE", "path");
    public static final ErrorCode SCHEMA_INCOMPATIBLE =
            define("PF-PLAN-0002", "SCHEMA_INCOMPATIBLE", "node", "upstream", "reason");
    public static final ErrorCode DELIVERY_UNSUPPORTED =
            define("PF-PLAN-0003", "DELIVERY_UNSUPPORTED", "node", "guarantee");
    public static final ErrorCode ROUTE_CONFLICT = define("PF-PLAN-0004", "ROUTE_CONFLICT", "table", "routes");
    public static final ErrorCode UPSERT_REQUIRES_PK = define("PF-PLAN-0005", "UPSERT_REQUIRES_PK", "node", "table");
    public static final ErrorCode CHANGELOG_INCOMPATIBLE =
            define("PF-PLAN-0006", "CHANGELOG_INCOMPATIBLE", "node", "upstream", "accepted", "produced");
    public static final ErrorCode EXPRESSION_INVALID =
            define("PF-PLAN-0007", "EXPRESSION_INVALID", "expression", "line", "column", "reason");
    public static final ErrorCode ROLLBACK_UNSUPPORTED = define("PF-PLAN-0008", "ROLLBACK_UNSUPPORTED", "node", "mode");
    public static final ErrorCode INPUT_INVALID = define("PF-PLAN-0009", "INPUT_INVALID", "node", "input", "reason");
    public static final ErrorCode NODE_DISCONNECTED = define("PF-PLAN-0010", "NODE_DISCONNECTED", "node");
    public static final ErrorCode BOUNDEDNESS_MISMATCH = define("PF-PLAN-0011", "BOUNDEDNESS_MISMATCH", "node");
    public static final ErrorCode TOPOLOGY_UNSUPPORTED =
            define("PF-PLAN-0012", "TOPOLOGY_UNSUPPORTED", "node", "reason");

    // Plugins
    public static final ErrorCode PLUGIN_NOT_FOUND = define("PF-PLUG-0001", "PLUGIN_NOT_FOUND", "plugin", "type");
    public static final ErrorCode PLUGIN_API_INCOMPATIBLE =
            define("PF-PLUG-0002", "PLUGIN_API_INCOMPATIBLE", "plugin", "required", "provided");
    public static final ErrorCode DRIVER_MISSING = define("PF-PLUG-0003", "DRIVER_MISSING", "plugin", "driver");
    public static final ErrorCode PLUGIN_LOAD_FAILED = define("PF-PLUG-0004", "PLUGIN_LOAD_FAILED", "plugin", "reason");
    public static final ErrorCode PLUGIN_FORBIDDEN_CALL =
            define("PF-PLUG-0005", "PLUGIN_FORBIDDEN_CALL", "plugin", "call", "location");
    public static final ErrorCode PLUGIN_TIMEOUT =
            define("PF-PLUG-0006", "PLUGIN_TIMEOUT", "plugin", "operation", "timeoutMs");
    public static final ErrorCode PLUGIN_CALL_FAILED =
            define("PF-PLUG-0007", "PLUGIN_CALL_FAILED", "plugin", "operation", "reason");
    public static final ErrorCode COMPONENT_AMBIGUOUS =
            define("PF-PLUG-0008", "COMPONENT_AMBIGUOUS", "type", "identifier", "plugins");

    // Data
    public static final ErrorCode CONSTRAINT_VIOLATION =
            define("PF-DATA-0004", "CONSTRAINT_VIOLATION", "table", "column", "reason");
    public static final ErrorCode EXPRESSION_ERROR = define("PF-DATA-0006", "EXPRESSION_ERROR", "expression", "reason");

    // Sources
    public static final ErrorCode SOURCE_FAILED = define("PF-SRC-0001", "SOURCE_FAILED", "node", "reason");

    // Sinks
    public static final ErrorCode SINK_FAILED = define("PF-SINK-0001", "SINK_FAILED", "node", "reason");
    public static final ErrorCode COMMIT_FAILED = define("PF-SINK-0003", "COMMIT_FAILED", "node", "reason");

    // Resources
    public static final ErrorCode CANCEL_TIMEOUT =
            define("PF-RES-0005", "CANCEL_TIMEOUT", "node", "subtask", "timeoutMs");
    public static final ErrorCode PORT_IN_USE = define("PF-RES-0006", "PORT_IN_USE", "port");

    // Cluster and processes
    public static final ErrorCode SERVER_START_FAILED = define("PF-CLUS-0002", "SERVER_START_FAILED", "reason", "log");
    public static final ErrorCode DOCKER_NOT_AVAILABLE = define("PF-CLUS-0003", "DOCKER_NOT_AVAILABLE", "detail");
    public static final ErrorCode DEV_ENVIRONMENT_FAILED =
            define("PF-CLUS-0004", "DEV_ENVIRONMENT_FAILED", "action", "exit");

    // Internal
    public static final ErrorCode INTERNAL = define("PF-SYS-0001", "INTERNAL", "detail");

    private final String id;
    private final String name;
    private final ErrorCategory category;
    private final List<String> params;

    private ErrorCode(String id, String name, ErrorCategory category, List<String> params) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.params = params;
    }

    /**
     * Defines an error code.
     *
     * @param id the code, e.g. {@code PF-CONF-0001}
     * @param name the symbolic name in upper snake case, e.g. {@code OPTION_REQUIRED}
     * @param params the names of the parameters its texts use
     * @throws IllegalArgumentException if the id, name or parameters are malformed
     */
    public static ErrorCode define(String id, String name, String... params) {
        Matcher matcher = FORMAT.matcher(id);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Malformed error code: " + id);
        }
        if (!NAME_FORMAT.matcher(name).matches()) {
            throw new IllegalArgumentException("Error code name must be upper snake case: " + name);
        }
        ErrorCategory category = categoryOf(matcher.group(1), id);
        List<String> list = List.of(params);
        if (list.stream().distinct().count() != list.size()) {
            throw new IllegalArgumentException("Duplicate parameter in " + id + ": " + list);
        }
        return new ErrorCode(id, name, category, list);
    }

    static ErrorCategory categoryOf(String category, String id) {
        try {
            return ErrorCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown error category in " + id, e);
        }
    }

    /** The code, e.g. {@code PF-CONF-0001}. */
    public String id() {
        return id;
    }

    /** The symbolic name, e.g. {@code OPTION_REQUIRED}. */
    public String name() {
        return name;
    }

    public ErrorCategory category() {
        return category;
    }

    /** Parameter names, in declaration order. */
    public List<String> params() {
        return params;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ErrorCode code && code.id.equals(id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id + " " + name;
    }
}
