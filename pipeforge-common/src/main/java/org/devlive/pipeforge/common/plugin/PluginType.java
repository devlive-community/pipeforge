/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.plugin;

import java.util.Locale;
import java.util.Optional;

/** Component types a plugin package can provide. */
public enum PluginType {
    SOURCE("org.devlive.pipeforge.api.factory.SourceFactory"),
    SINK("org.devlive.pipeforge.api.factory.SinkFactory"),
    TRANSFORM("org.devlive.pipeforge.api.factory.TransformFactory"),
    CATALOG("org.devlive.pipeforge.api.factory.CatalogFactory"),
    DIALECT(null),
    FORMAT(null),
    DRIVER(null),
    STORAGE(null),
    FUNCTION(null);

    private final String factoryInterface;

    PluginType(String factoryInterface) {
        this.factoryInterface = factoryInterface;
    }

    /** The factory interface a provided class of this type implements, if the API defines one yet. */
    public Optional<String> factoryInterface() {
        return Optional.ofNullable(factoryInterface);
    }

    /** The lower-case name used in plugin.yaml and jobs. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<PluginType> parse(String text) {
        for (PluginType type : values()) {
            if (type.id().equals(text)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
