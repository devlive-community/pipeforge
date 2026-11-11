/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import java.util.List;
import java.util.Objects;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.source.Boundedness;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.core.job.NodeKind;
import org.jspecify.annotations.Nullable;

/**
 * A node of the logical plan.
 *
 * @param id the node id
 * @param kind source, transform or sink
 * @param type the component identifier
 * @param plugin {@code id@version} of the plugin that provides the component
 * @param inputs the upstream node ids
 * @param inputsImplied whether the inputs were taken from the preceding node
 * @param parallelism the parallelism of the node
 * @param boundedness whether a source ends; {@code null} for other nodes
 * @param config the effective options, as passed to the factory
 * @param options the effective options with their origin, for explain
 * @param inputTables the tables the node receives, merged across its inputs
 * @param outputTables the tables the node produces; empty for sinks
 * @param component the resolved component, used to create the runtime instances
 */
public record PlannedNode(
        String id,
        NodeKind kind,
        String type,
        String plugin,
        List<String> inputs,
        boolean inputsImplied,
        int parallelism,
        @Nullable Boundedness boundedness,
        ReadonlyConfig config,
        List<PlannedOption> options,
        List<CatalogTable> inputTables,
        List<CatalogTable> outputTables,
        Component component) {

    public PlannedNode {
        Objects.requireNonNull(id, "id");
        inputs = List.copyOf(inputs);
        options = List.copyOf(options);
        inputTables = List.copyOf(inputTables);
        outputTables = List.copyOf(outputTables);
    }
}
