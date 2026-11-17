/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.metastore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.server.config.ServerProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The migrations on every database the metastore can be in. */
class MetastoreMigrationsTest {

    private static final Map<MetastoreVendor, JdbcDatabaseContainer<?>> SERVERS = new ConcurrentHashMap<>();
    private static final Set<String> TABLES =
            Set.of("job_run", "job_run_attempt", "job_run_transition", "job_run_metrics", "worker");
    private static int databases;

    @AfterAll
    static void stop() {
        SERVERS.values().forEach(JdbcDatabaseContainer::stop);
        SERVERS.clear();
    }

    /** The settings of a fresh, empty metastore on {@code vendor}. */
    @SuppressWarnings("resource") // stopped after all tests
    static ServerProperties.Metastore fresh(MetastoreVendor vendor) throws Exception {
        String name = "m" + (++databases);
        if (vendor == MetastoreVendor.H2) {
            return settings("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "", null);
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        JdbcDatabaseContainer<?> server = SERVERS.computeIfAbsent(vendor, v -> {
            JdbcDatabaseContainer<?> created = v == MetastoreVendor.MYSQL
                    ? new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                    : new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
            created.withUsername("root".equals(created.getUsername()) ? "test" : created.getUsername());
            created.start();
            return created;
        });
        try (Connection admin = java.sql.DriverManager.getConnection(
                        server.getJdbcUrl(),
                        vendor == MetastoreVendor.MYSQL ? "root" : server.getUsername(),
                        server.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
            if (vendor == MetastoreVendor.MYSQL) {
                statement.execute("GRANT ALL ON " + name + ".* TO '" + server.getUsername() + "'@'%'");
            }
        }
        String url = server.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return settings(url, server.getUsername(), server.getPassword(), null);
    }

    static ServerProperties.Metastore settings(String url, String user, String password, Path driverDir) {
        return new ServerProperties.Metastore(url, user, password, driverDir, 4, true, java.time.Duration.ofDays(30));
    }

    static Set<String> tables(HikariDataSource dataSource) throws Exception {
        Set<String> tables = new TreeSet<>();
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection
                        .getMetaData()
                        .getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    @ParameterizedTest
    @EnumSource(MetastoreVendor.class)
    void migratesAnEmptyMetastoreOnceAndKeepsRows(MetastoreVendor vendor) throws Exception {
        ServerProperties.Metastore settings = fresh(vendor);
        try (HikariDataSource dataSource = MetastoreDataSources.create(settings)) {
            MetastoreMigrations migrations = new MetastoreMigrations(dataSource, vendor, settings.safeUrl());
            assertThat(migrations.version()).isNull();
            assertThat(migrations.pending())
                    .extracting(MetastoreMigrations.Pending::version)
                    .contains("1");

            MetastoreMigrations.Result first = migrations.migrate();
            assertThat(first.applied()).isPositive();
            assertThat(tables(dataSource)).containsAll(TABLES).contains(MetastoreMigrations.EXPAND_HISTORY);
            assertThat(migrations.pending()).isEmpty();
            MetastoreMigrations.Result again = migrations.migrate();
            assertThat(again.applied()).isZero();
            assertThat(again.version()).isEqualTo(first.version()).isEqualTo(migrations.version());
            // no contract scripts yet: finalizing changes nothing
            assertThat(migrations.finalizeUpgrade().applied()).isZero();

            // rows with text beyond the BMP, long text and generated ids
            JdbcClient jdbc = JdbcClient.create(dataSource);
            Timestamp now = Timestamp.from(Instant.parse("2026-11-17T08:00:00.123Z"));
            jdbc.sql("INSERT INTO job_run (id, job_name, job_yaml, status, submitted_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")
                    .params("r1", "订单 😀", "x".repeat(100_000), "RUNNING", now, now)
                    .update();
            jdbc.sql("INSERT INTO job_run_transition (run_id, attempt, from_status, to_status, occurred_at)"
                            + " VALUES ('r1', 1, NULL, 'RUNNING', ?)")
                    .param(now)
                    .update();
            jdbc.sql("INSERT INTO job_run_transition (run_id, attempt, from_status, to_status, occurred_at)"
                            + " VALUES ('r1', 1, 'RUNNING', 'FINISHED', ?)")
                    .param(now)
                    .update();
            assertThat(jdbc.sql("SELECT job_name FROM job_run WHERE id = 'r1'")
                            .query(String.class)
                            .single())
                    .isEqualTo("订单 😀");
            assertThat(jdbc.sql("SELECT LENGTH(job_yaml) FROM job_run")
                            .query(Long.class)
                            .single())
                    .isEqualTo(100_000L);
            assertThat(jdbc.sql("SELECT id FROM job_run_transition ORDER BY id")
                            .query(Long.class)
                            .list())
                    .hasSize(2)
                    .isSorted()
                    .doesNotHaveDuplicates();

            // the queries of the run endpoints, on this database
            var repository = new org.devlive.pipeforge.server.run.RunRepository(
                    dataSource,
                    new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)));
            repository.insert("r2", "orders", "yaml", null, Instant.parse("2026-11-17T08:00:01Z"));
            repository.sample(
                    "r2",
                    new org.devlive.pipeforge.server.run.RunMetrics(
                            Instant.parse("2026-11-17T08:00:02Z"),
                            1000,
                            40,
                            30,
                            0,
                            0,
                            0,
                            0,
                            10,
                            40,
                            30,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            1,
                            1,
                            1,
                            0,
                            List.of(),
                            List.of()),
                    "{}");
            var all = java.util.EnumSet.noneOf(org.devlive.pipeforge.server.run.RunState.class);
            var page = repository.list(all, null, 1, null);
            assertThat(page.runs()).singleElement().satisfies(run -> {
                assertThat(run.id()).isEqualTo("r2");
                assertThat(run.rowsRead()).isEqualTo(40);
                assertThat(run.rowsWritten()).isEqualTo(30);
            });
            assertThat(repository.list(all, null, 1, page.next()).runs())
                    .extracting(org.devlive.pipeforge.server.run.Runs.Run::id)
                    .containsExactly("r1");
            assertThat(repository.latestMetrics("r2")).contains("{}");
        }
    }

    @ParameterizedTest
    @EnumSource(MetastoreVendor.class)
    void everyEarlierVersionRunsOnTheLatestSchema(MetastoreVendor vendor) throws Exception {
        ServerProperties.Metastore settings = fresh(vendor);
        try (HikariDataSource dataSource = MetastoreDataSources.create(settings)) {
            MetastoreMigrations migrations = new MetastoreMigrations(dataSource, vendor, settings.safeUrl());
            List<String> versions = versions(vendor);
            // upgrading step by step reaches the same schema as at once
            for (String version : versions) {
                assertThat(migrations.migrate(version).version()).isEqualTo(version);
            }
            migrations.migrate();
            // rolling back: a server of an earlier version starts on the expanded schema
            for (String version : versions) {
                assertThat(migrations.migrate(version).applied())
                        .as("version %s", version)
                        .isZero();
            }
        }
    }

    @Test
    void aChangedScriptThatWasAppliedStopsTheMigration() throws Exception {
        ServerProperties.Metastore settings = fresh(MetastoreVendor.H2);
        try (HikariDataSource dataSource = MetastoreDataSources.create(settings)) {
            MetastoreMigrations migrations =
                    new MetastoreMigrations(dataSource, MetastoreVendor.H2, settings.safeUrl());
            migrations.migrate();
            JdbcClient.create(dataSource)
                    .sql("UPDATE \"" + MetastoreMigrations.EXPAND_HISTORY
                            + "\" SET \"checksum\" = 1 WHERE \"version\" = '1'")
                    .update();
            assertThatThrownBy(migrations::migrate).isInstanceOfSatisfying(PipeForgeException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.METASTORE_MIGRATION_FAILED);
                assertThat(e.getMessage()).contains("checksum");
            });
        }
    }

    @Test
    void readsTheDriverOfADatabaseTheServerDoesNotShipFromADirectory(@TempDir Path dir) throws Exception {
        ServerProperties.Metastore mysql = fresh(MetastoreVendor.MYSQL);
        Path jar = Path.of(com.mysql.cj.jdbc.Driver.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Files.copy(jar, dir.resolve(String.valueOf(jar.getFileName())));
        Files.writeString(dir.resolve("notes.txt"), "not a jar");
        ServerProperties.Metastore withDir = settings(mysql.url(), mysql.username(), mysql.password(), dir);
        assertThat(MetastoreDataSources.driver(withDir).getClass().getClassLoader())
                .as("the driver of the directory, not of the server")
                .isNotSameAs(MetastoreMigrationsTest.class.getClassLoader());
        try (HikariDataSource dataSource = MetastoreDataSources.create(withDir)) {
            MetastoreMigrations migrations =
                    new MetastoreMigrations(dataSource, MetastoreVendor.MYSQL, withDir.safeUrl());
            assertThat(migrations.migrate().applied()).isPositive();
        }
    }

    @Test
    void reportsWhatStopsTheServerFromReachingTheMetastore(@TempDir Path dir) {
        assertThatThrownBy(() -> MetastoreDataSources.create(
                        settings("jdbc:h2:file:" + dir.resolve("db") + ";IFEXISTS=TRUE", "sa", "", null)))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.METASTORE_UNAVAILABLE);
                    assertThat(e.params()).containsEntry("url", "jdbc:h2:file:" + dir.resolve("db") + ";IFEXISTS=TRUE");
                });
        assertThatThrownBy(() -> MetastoreDataSources.create(
                        settings("jdbc:postgresql://db.invalid:5432/x?password=secret", "u", "p", dir)))
                .isInstanceOfSatisfying(PipeForgeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.METASTORE_UNAVAILABLE);
                    assertThat(e.getMessage())
                            .contains("no driver in " + dir)
                            .as("the URL without its query")
                            .doesNotContain("secret");
                });
        assertThatThrownBy(() -> MetastoreVendor.of("jdbc:oracle:thin:@db:1521/x"))
                .isInstanceOfSatisfying(
                        PipeForgeException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SETTING_INVALID));
        assertThat(MetastoreVendor.of("jdbc:postgresql://db/x")).isEqualTo(MetastoreVendor.POSTGRESQL);
    }

    /** The expand versions there are scripts for, in order. */
    static List<String> versions(MetastoreVendor vendor) throws Exception {
        List<String> versions = new ArrayList<>();
        try (Stream<Path> files =
                Files.list(MetastoreScriptsTest.RESOURCES.resolve("db/metastore/expand/" + vendor.folder()))) {
            files.map(f -> String.valueOf(f.getFileName()))
                    .map(n -> n.substring(1, n.indexOf("__")))
                    .sorted(java.util.Comparator.comparing(Integer::parseInt))
                    .forEach(versions::add);
        }
        return versions;
    }
}
