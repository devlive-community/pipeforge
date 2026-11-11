/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import java.util.List;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.core.plan.PlannedNode;

/** The factory context of a planned node, to create its component at run time as the planner did. */
record NodeContext(
        String nodeName,
        ReadonlyConfig options,
        JobContext job,
        List<CatalogTable> inputTables,
        ClassLoader classLoader)
        implements FactoryContext {

    static NodeContext of(PlannedNode node, JobContext job) {
        return new NodeContext(
                node.id(),
                node.config(),
                job,
                node.inputTables(),
                node.component().classLoader());
    }
}
