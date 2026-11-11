/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.transform;

import java.util.List;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.table.CatalogTable;

/** Runtime context of a transform instance. */
@Stable
public interface TransformContext {

    JobContext job();

    int subtaskIndex();

    int parallelism();

    /** The input tables, as inferred while planning. */
    List<CatalogTable> inputTables();

    ClassLoader classLoader();

    /**
     * The expression language, to compile the expressions of this instance. Engines that cannot
     * evaluate expressions throw {@link UnsupportedOperationException}.
     */
    default ExpressionCompiler expressions() {
        throw new UnsupportedOperationException("Expressions are not available in this context");
    }
}
