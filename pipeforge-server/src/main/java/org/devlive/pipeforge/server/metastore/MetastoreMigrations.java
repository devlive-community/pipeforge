/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.metastore;

import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.jspecify.annotations.Nullable;

/**
 * The migrations of the metastore, in two steps (design 23, section 6.4):
 *
 * <ul>
 *   <li><b>expand</b> scripts ({@code db/metastore/expand/<vendor>}) only add: tables, nullable
 *       columns, indexes. The server applies them when it starts, so the previous version keeps
 *       working on the expanded schema and an upgrade can be rolled back.
 *   <li><b>contract</b> scripts ({@code db/metastore/contract/<vendor>}) drop, rename or change
 *       what the previous version needs; they run only when an upgrade is finalized.
 * </ul>
 *
 * <p>Each step keeps its own history table. Flyway locks it while it migrates, so of several
 * servers that start together only one migrates. Applied scripts are never changed: Flyway checks
 * their checksums, and the build checks every script against {@code db/metastore/scripts.sha256}.
 */
public final class MetastoreMigrations {

    /** The history of the expand step. */
    public static final String EXPAND_HISTORY = "pipeforge_schema_history";

    /** The history of the contract step. */
    public static final String CONTRACT_HISTORY = "pipeforge_schema_contract";

    private final DataSource dataSource;
    private final MetastoreVendor vendor;
    private final String url;

    public MetastoreMigrations(DataSource dataSource, MetastoreVendor vendor, String url) {
        this.dataSource = dataSource;
        this.vendor = vendor;
        this.url = url;
    }

    /** What a migration did: the scripts it applied and the schema version after it. */
    public record Result(int applied, @Nullable String version) {}

    /** A script not applied yet, for a dry run. */
    public record Pending(String version, String description, String script) {}

    /**
     * Applies the pending expand scripts.
     *
     * @throws PipeForgeException {@code PF-CLUS-0014} when a script fails
     */
    public Result migrate() {
        return migrate(flyway("expand", EXPAND_HISTORY, null));
    }

    /** Applies the expand scripts up to {@code version}, as the server of that version would. */
    public Result migrate(String version) {
        return migrate(flyway("expand", EXPAND_HISTORY, version));
    }

    /**
     * Applies the pending contract scripts, once an upgrade is confirmed: after it, the previous
     * version of the server can no longer run on the metastore.
     */
    public Result finalizeUpgrade() {
        return migrate(flyway("contract", CONTRACT_HISTORY, null));
    }

    /** The expand scripts the next {@link #migrate()} applies. */
    public List<Pending> pending() {
        try {
            return Arrays.stream(flyway("expand", EXPAND_HISTORY, null).info().pending())
                    .map(info ->
                            new Pending(String.valueOf(info.getVersion()), info.getDescription(), info.getScript()))
                    .toList();
        } catch (FlywayException e) {
            throw failed(e);
        }
    }

    /** The expand version of the schema; {@code null} before the first migration. */
    public @Nullable String version() {
        try {
            MigrationInfo current =
                    flyway("expand", EXPAND_HISTORY, null).info().current();
            return current == null ? null : String.valueOf(current.getVersion());
        } catch (FlywayException e) {
            throw failed(e);
        }
    }

    public MetastoreVendor vendor() {
        return vendor;
    }

    private Result migrate(Flyway flyway) {
        try {
            MigrateResult result = flyway.migrate();
            String version =
                    result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion;
            return new Result(result.migrationsExecuted, version);
        } catch (FlywayException e) {
            throw failed(e);
        }
    }

    private Flyway flyway(String step, String history, @Nullable String target) {
        var config = Flyway.configure(MetastoreMigrations.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/metastore/" + step + "/" + vendor.folder())
                .table(history)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .failOnMissingLocations(false);
        if (target != null) {
            config.target(target);
        }
        if (step.equals("contract")) {
            // the contract step starts on the schema the expand step built
            config.baselineOnMigrate(true).baselineVersion("0");
        }
        return config.load();
    }

    private PipeForgeException failed(FlywayException e) {
        return PipeForgeException.of(ErrorCode.METASTORE_MIGRATION_FAILED)
                .param("url", url)
                .param("reason", String.valueOf(e.getMessage()))
                .cause(e)
                .build();
    }
}
