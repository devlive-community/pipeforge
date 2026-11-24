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
            Set.of("job_run", "job_run_attempt", "job_run_transition", "job_run_metrics", "worker", "checkpoint");
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

            // checkpoints, and an attempt written as the previous version writes it
            jdbc.sql("INSERT INTO job_run_attempt (run_id, attempt, status, started_at) VALUES ('r1', 1, 'RUNNING', ?)")
                    .param(now)
                    .update();
            jdbc.sql("UPDATE job_run_attempt SET restore_path = ?, restore_checkpoint_id = 4, last_checkpoint_id = 7"
                            + " WHERE run_id = 'r1'")
                    .param("s3://checkpoints/pipeforge/job-1/savepoints/upgrade-1/")
                    .update();
            for (long id = 5; id <= 7; id++) {
                jdbc.sql("INSERT INTO checkpoint (run_id, checkpoint_id, job_name, attempt, type, status,"
                                + " trigger_reason, name, triggered_at, completed_at, size_bytes, path, error_code,"
                                + " error_message, tasks_json) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(
                                "r1",
                                id,
                                "订单 😀",
                                id == 6 ? "SAVEPOINT" : "CHECKPOINT",
                                id == 7 ? "FAILED" : "COMPLETED",
                                id == 6 ? "SAVEPOINT" : "PERIODIC",
                                id == 6 ? "before-upgrade" : null,
                                now,
                                id == 7 ? null : now,
                                id == 7 ? null : 1L << 33,
                                "job-1/chk-" + id + "/",
                                id == 7 ? "PF-CKPT-0001" : null,
                                id == 7 ? "x".repeat(70_000) : null,
                                "[{\"task\":\"src-0\",\"acknowledged\":true}]")
                        .update();
            }
            assertThat(jdbc.sql("SELECT checkpoint_id FROM checkpoint WHERE job_name = ? AND status = 'COMPLETED'"
                                    + " ORDER BY checkpoint_id DESC")
                            .param("订单 😀")
                            .query(Long.class)
                            .list())
                    .as("the latest completed checkpoint of a job first")
                    .containsExactly(6L, 5L);
            assertThat(jdbc.sql("SELECT size_bytes FROM checkpoint WHERE checkpoint_id = 5")
                            .query(Long.class)
                            .single())
                    .isEqualTo(1L << 33);
            assertThat(jdbc.sql("SELECT LENGTH(error_message) FROM checkpoint WHERE checkpoint_id = 7")
                            .query(Long.class)
                            .single())
                    .isEqualTo(70_000L);
            assertThat(jdbc.sql("SELECT last_checkpoint_id FROM job_run_attempt WHERE run_id = 'r1'")
                            .query(Long.class)
                            .single())
                    .isEqualTo(7L);
        }
    }

    @ParameterizedTest
    @EnumSource(MetastoreVendor.class)
    void theMasterRecordsCheckpointsInTheMigratedSchema(MetastoreVendor vendor) throws Exception {
        ServerProperties.Metastore settings = fresh(vendor);
        try (HikariDataSource dataSource = MetastoreDataSources.create(settings)) {
            var store = new org.devlive.pipeforge.engine.master.checkpoint.JdbcCheckpointStore(
                    dataSource, settings.safeUrl());
            org.assertj.core.api.Assertions.assertThatThrownBy(store::check)
                    .as("before the server migrated it")
                    .hasMessageContaining("PF-CLUS-0013")
                    .hasMessageContaining("has no checkpoint table");
            new MetastoreMigrations(dataSource, vendor, settings.safeUrl()).migrate();
            store.check();

            Instant at = Instant.parse("2026-11-23T08:00:00.123Z");
            JdbcClient.create(dataSource)
                    .sql(
                            "INSERT INTO job_run_attempt (run_id, attempt, status, started_at) VALUES ('r1', 1, 'RUNNING', ?)")
                    .param(Timestamp.from(at))
                    .update();
            assertThat(store.lastCheckpointId("r1")).isZero();
            for (long id = 1; id <= 3; id++) {
                store.begin(new org.devlive.pipeforge.engine.master.checkpoint.CheckpointStore.Checkpoint(
                        "r1",
                        id,
                        "订单 😀",
                        1,
                        id == 2
                                ? org.devlive.pipeforge.engine.rpc.CheckpointType.SAVEPOINT
                                : org.devlive.pipeforge.engine.rpc.CheckpointType.CHECKPOINT,
                        org.devlive.pipeforge.engine.master.checkpoint.CheckpointStore.Status.IN_PROGRESS,
                        id == 2 ? "SAVEPOINT" : "PERIODIC",
                        id == 2 ? "before-upgrade" : null,
                        at.plusSeconds(id),
                        null,
                        null,
                        "r1/chk-" + id + "/",
                        null,
                        null,
                        null));
            }
            store.complete("r1", 1, 1, at.plusSeconds(5), 1L << 33, "[{\"task\":\"r1/src/0/0\"}]");
            store.complete("r1", 2, 1, at.plusSeconds(6), 10, "[]");
            store.fail("r1", 3, at.plusSeconds(7), "PF-CKPT-0001", "x".repeat(70_000));
            assertThat(store.lastCheckpointId("r1")).isEqualTo(3);
            assertThat(store.lastCheckpointId("r2")).isZero();

            var list = store.list("r1");
            assertThat(list)
                    .extracting(
                            org.devlive.pipeforge.engine.master.checkpoint.CheckpointStore.Checkpoint::checkpointId,
                            c -> c.status().name())
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(3L, "FAILED"),
                            org.assertj.core.groups.Tuple.tuple(2L, "COMPLETED"),
                            org.assertj.core.groups.Tuple.tuple(1L, "COMPLETED"));
            var first = list.get(2);
            assertThat(first.size()).isEqualTo(1L << 33);
            assertThat(first.jobName()).isEqualTo("订单 😀");
            assertThat(first.completedAt()).isEqualTo(at.plusSeconds(5));
            assertThat(first.tasksJson()).contains("r1/src/0/0");
            assertThat(list.get(1).name()).isEqualTo("before-upgrade");
            assertThat(list.get(0).errorMessage()).hasSize(70_000);
            assertThat(list.get(0).size()).isNull();
            assertThat(JdbcClient.create(dataSource)
                            .sql("SELECT last_checkpoint_id FROM job_run_attempt WHERE run_id = 'r1'")
                            .query(Long.class)
                            .single())
                    .as("the last checkpoint of the attempt")
                    .isEqualTo(2L);

            store.delete("r1", 1);
            assertThat(store.list("r1")).hasSize(2);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.complete("r1", 9, 1, at, 1, "[]"))
                    .hasMessageContaining("the checkpoint is not recorded");
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

    /** A master that fails the first attempt of every run with a lost connection, and finishes the next. */
    static final class FlakyMaster implements org.devlive.pipeforge.server.run.MasterClient {
        final List<Submission> submitted = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<String, Integer> attempts = new ConcurrentHashMap<>();

        @Override
        public String address() {
            return "master:1";
        }

        @Override
        public void submit(Submission submission) {
            submitted.add(submission);
            attempts.put(submission.runId(), submission.attempt());
        }

        @Override
        public void cancel(String runId, String reason) {}

        @Override
        public java.util.Optional<Status> status(String runId) {
            int attempt = attempts.getOrDefault(runId, 1);
            return java.util.Optional.of(
                    attempt == 1
                            ? new Status(
                                    org.devlive.pipeforge.server.run.RunState.FAILED,
                                    java.time.Instant.EPOCH,
                                    new org.devlive.pipeforge.server.run.Runs.RunError("PF-CONN-0003", "lost"),
                                    List.of(),
                                    1)
                            : new Status(
                                    org.devlive.pipeforge.server.run.RunState.SUCCESS,
                                    java.time.Instant.EPOCH,
                                    null,
                                    List.of(),
                                    attempt));
        }

        @Override
        public java.util.Optional<org.devlive.pipeforge.server.run.RunMetrics> metrics(String runId) {
            int attempt = attempts.getOrDefault(runId, 1);
            return java.util.Optional.of(new org.devlive.pipeforge.server.run.RunMetrics(
                    java.time.Instant.EPOCH.plusSeconds(attempt),
                    100,
                    attempt * 10L,
                    attempt * 10L,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    List.of(),
                    List.of()));
        }

        @Override
        public org.devlive.pipeforge.server.run.ClusterView cluster() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<String> analysis(String runId) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<List<org.devlive.pipeforge.server.run.RunMetrics.Split>> splits(String runId) {
            return java.util.Optional.empty();
        }

        @Override
        public org.devlive.pipeforge.server.run.RunMetrics.LogChunk readLog(
                String runId, org.devlive.pipeforge.server.run.RunMetrics.Task task, LogQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    @ParameterizedTest
    @EnumSource(MetastoreVendor.class)
    void aFailedAttemptWaitsAndIsRetriedFromTheLastCheckpoint(MetastoreVendor vendor) throws Exception {
        ServerProperties.Metastore settings = fresh(vendor);
        try (HikariDataSource dataSource = MetastoreDataSources.create(settings)) {
            new MetastoreMigrations(dataSource, vendor, settings.safeUrl()).migrate();
            var repository = new org.devlive.pipeforge.server.run.RunRepository(
                    dataSource,
                    new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)));
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            java.time.Instant submitted = java.time.Instant.parse("2026-11-23T08:00:00Z");
            var submission = new org.devlive.pipeforge.server.run.RunRetries.Resubmission(
                    Map.of("day", "2026-11-23"),
                    submitted.toEpochMilli(),
                    Map.of("fake", "0.1.0"),
                    "exactly-once",
                    new org.devlive.pipeforge.server.run.RunRetries.Retry(
                            2, 60_000, 600_000, 2, List.of("CONN"), "auto", null),
                    3_600_000L);
            repository.insert("r1", "订单 😀", "yaml", null, submitted, json.writeValueAsString(submission));
            repository.transition(
                    "r1",
                    org.devlive.pipeforge.server.run.RunState.QUEUE,
                    org.devlive.pipeforge.server.run.RunState.RUNNING,
                    submitted,
                    submitted,
                    null,
                    "running");
            var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
            jdbc.sql("INSERT INTO checkpoint (run_id, checkpoint_id, job_name, attempt, type, status, trigger_reason,"
                            + " triggered_at, completed_at, size_bytes, path) VALUES ('r1', 3, 'job', 1, 'CHECKPOINT',"
                            + " 'COMPLETED', 'PERIODIC', ?, ?, 10, 'r1/chk-3/')")
                    .params(
                            java.time.LocalDateTime.of(2026, 11, 23, 8, 0),
                            java.time.LocalDateTime.of(2026, 11, 23, 8, 0))
                    .update();

            FlakyMaster master = new FlakyMaster();
            var now = new java.util.concurrent.atomic.AtomicReference<>(submitted.plusSeconds(30));
            java.time.Clock clock = new java.time.Clock() {
                @Override
                public java.time.ZoneId getZone() {
                    return java.time.ZoneOffset.UTC;
                }

                @Override
                public java.time.Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public java.time.Instant instant() {
                    return now.get();
                }
            };
            var tracker = new org.devlive.pipeforge.server.run.RunTracker(
                    repository,
                    master,
                    clock,
                    new ServerProperties(
                            new ServerProperties.Plugin(
                                    List.of(), Path.of("cache"), new ServerProperties.Dev(false, null)),
                            new ServerProperties.Job("UTC", Map.of()),
                            settings,
                            new ServerProperties.Master(
                                    null,
                                    "",
                                    new ServerProperties.Tls(false, null, "", null, ""),
                                    java.time.Duration.ofMillis(50),
                                    java.time.Duration.ofMillis(50))),
                    json);

            tracker.poll();
            var waiting = repository.find("r1").orElseThrow();
            assertThat(waiting.status()).isEqualTo(org.devlive.pipeforge.server.run.RunState.RETRY_WAIT);
            assertThat(waiting.retryAt()).isEqualTo(submitted.plusSeconds(90));
            assertThat(waiting.error().code()).isEqualTo("PF-CONN-0003");
            tracker.poll();
            assertThat(master.submitted).as("not before the backoff").isEmpty();

            now.set(submitted.plusSeconds(91));
            tracker.poll();
            assertThat(master.submitted).singleElement().satisfies(next -> {
                assertThat(next.attempt()).isEqualTo(2);
                assertThat(next.restorePath()).isEqualTo("r1/chk-3/");
                assertThat(next.variables()).containsEntry("day", "2026-11-23");
                assertThat(next.plugins()).containsEntry("fake", "0.1.0");
                assertThat(next.scheduledTime()).isEqualTo(submitted);
                assertThat(next.timeout())
                        .as("env.timeout.run less the 30 s attempt 1 ran")
                        .isEqualTo(java.time.Duration.ofSeconds(3570));
            });
            tracker.poll();
            var done = repository.detail("r1");
            assertThat(done.run().status()).isEqualTo(org.devlive.pipeforge.server.run.RunState.SUCCESS);
            assertThat(done.run().attempt()).isEqualTo(2);
            assertThat(done.run().error()).isNull();
            assertThat(done.run().rowsWritten())
                    .as("counted across the attempts")
                    .isEqualTo(30L);
            assertThat(done.attempts())
                    .extracting(a -> a.attempt(), a -> a.status(), a -> a.restorePath())
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    1, org.devlive.pipeforge.server.run.RunState.FAILED, null),
                            org.assertj.core.groups.Tuple.tuple(
                                    2, org.devlive.pipeforge.server.run.RunState.SUCCESS, "r1/chk-3/"));
            assertThat(repository.runningMs("r1")).isEqualTo(30_000L);
            assertThat(done.transitions())
                    .extracting(t -> t.attempt() + ":" + t.to())
                    .containsExactly("1:QUEUE", "1:RUNNING", "1:RETRY_WAIT", "2:QUEUE", "2:SUCCESS");
        }
    }
}
