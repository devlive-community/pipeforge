/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.explain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The structured explanation of a plan: the JSON output of {@code explain} and the response of
 * the server's explain endpoint. Sensitive option values are already masked.
 *
 * @param job the job settings
 * @param variables the resolved job variables
 * @param nodes the nodes in topological order
 * @param warnings problems that do not stop the job
 */
public record ExplainDocument(Job job, Map<String, String> variables, List<Node> nodes, List<String> warnings) {

    public ExplainDocument {
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        nodes = List.copyOf(nodes);
        warnings = List.copyOf(warnings);
    }

    /** The job settings. */
    public record Job(
            String name,
            @Nullable String description,
            String engine,
            String mode,
            boolean modeDerived,
            String delivery,
            int parallelism,
            String timezone) {}

    /** One node of the logical plan. */
    public record Node(
            String id,
            String kind,
            String type,
            String plugin,
            List<String> inputs,
            boolean inputsImplied,
            int parallelism,
            @Nullable String boundedness,
            List<Option> options,
            List<Table> inputTables,
            List<Table> outputTables) {

        public Node {
            inputs = List.copyOf(inputs);
            options = List.copyOf(options);
            inputTables = List.copyOf(inputTables);
            outputTables = List.copyOf(outputTables);
        }
    }

    /** The effective value of a node option and where it comes from: node, env, cluster or default. */
    public record Option(String key, @Nullable Object value, String origin, boolean sensitive) {}

    /** A table on an edge of the plan. */
    public record Table(String id, List<Column> columns, List<String> primaryKey, List<String> partitionKeys) {

        public Table {
            columns = List.copyOf(columns);
            primaryKey = List.copyOf(primaryKey);
            partitionKeys = List.copyOf(partitionKeys);
        }
    }

    /** A column with its logical type (without nullability) and whether it is nullable. */
    public record Column(
            String name,
            String type,
            boolean nullable,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String comment) {}
}
