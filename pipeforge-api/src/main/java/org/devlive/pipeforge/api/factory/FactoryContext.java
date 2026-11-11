/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.factory;

import java.util.List;
import java.util.Map;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.expression.ExpressionCompiler;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.table.CatalogTable;

/** What a factory gets to create a component: validated options and job information. */
@Stable
public interface FactoryContext {

    /** The node name in the job. */
    String nodeName();

    /** The options of the node, already validated against the factory's rule. */
    ReadonlyConfig options();

    /** Native configuration under a namespace, merged from the data source and the node. */
    default Map<String, String> nativeConfig(String namespace) {
        return options().nativeConfig(namespace);
    }

    JobContext job();

    /** The tables produced by the upstream nodes; empty for sources. */
    List<CatalogTable> inputTables();

    ClassLoader classLoader();

    /**
     * The expression language, to type-check expressions while planning (and to compile them when
     * the component is created at run time). Engines that cannot evaluate expressions throw {@link
     * UnsupportedOperationException}.
     */
    default ExpressionCompiler expressions() {
        throw new UnsupportedOperationException("Expressions are not available in this context");
    }
}
