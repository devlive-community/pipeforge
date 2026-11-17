/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.metastore;

import java.util.Locale;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;

/** The databases a metastore can be in; each has migration scripts of its own. */
public enum MetastoreVendor {
    H2("jdbc:h2:"),
    MYSQL("jdbc:mysql:"),
    POSTGRESQL("jdbc:postgresql:");

    private final String prefix;

    MetastoreVendor(String prefix) {
        this.prefix = prefix;
    }

    /** The directory of the vendor's scripts below {@code db/metastore/expand} and {@code contract}. */
    public String folder() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The vendor of a JDBC URL.
     *
     * @throws PipeForgeException {@code PF-CONF-0011} for a database the metastore does not support
     */
    public static MetastoreVendor of(String url) {
        for (MetastoreVendor vendor : values()) {
            if (url.startsWith(vendor.prefix)) {
                return vendor;
            }
        }
        throw PipeForgeException.of(ErrorCode.SETTING_INVALID)
                .param("key", "pipeforge.metastore.url")
                .param("source", "the server configuration")
                .param("reason", "the metastore is H2, MySQL or PostgreSQL (jdbc:h2:, jdbc:mysql:, jdbc:postgresql:)")
                .build();
    }
}
