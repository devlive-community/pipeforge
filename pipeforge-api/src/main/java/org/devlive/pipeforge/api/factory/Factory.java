/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.factory;

import java.util.EnumSet;
import java.util.Set;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.option.OptionRule;

/**
 * Entry point of a plugin component. Factories are discovered with {@link java.util.ServiceLoader}
 * from the plugin's class loader.
 */
@Stable
public interface Factory {

    /** The name used in jobs, e.g. {@code jdbc}, {@code mysql-cdc} or {@code field-mapper}. */
    String identifier();

    OptionRule optionRule();

    /** Engines this component runs on; all by default. */
    default Set<EngineKind> engines() {
        return EnumSet.allOf(EngineKind.class);
    }
}
