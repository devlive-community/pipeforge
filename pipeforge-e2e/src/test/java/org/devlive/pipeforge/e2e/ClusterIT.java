/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A cluster of the packaged distribution (a master, three workers and the server) copies a million
 * rows from MySQL to PostgreSQL, which must match row for row, with the run's metrics and analysis
 * on the server; then a worker is killed under a running job, which must fail and say why.
 */
@DisabledOnOs(OS.WINDOWS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClusterIT {

    static final int ROWS = 1_000_000;

    MySQLContainer mysql;
    PostgreSQLContainer postgres;
    Cluster cluster;

    @BeforeAll
    void start() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is needed for MySQL and PostgreSQL");
        mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4")).withDatabaseName("shop");
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
        mysql.start();
        postgres.start();
        loadMySql();
        createTargets();

        Path dist = Path.of(System.getProperty("pipeforge.dist"));
        Path dir = dist.getParent().getParent().resolve("e2e-run");
        Support.deleteTree(dir);
        Files.createDirectories(dir);
        cluster = new Cluster(dist, dir);
        Path master = cluster.startMaster(Duration.ofSeconds(6));
        Support.installDriverPlugin(master.resolve("plugins"));
        cluster.startWorker("worker-1", 4, "zone=a");
        cluster.startWorker("worker-2", 4, "zone=b");
        cluster.startWorker("worker-3", 4, "zone=c");
        cluster.startServer(master);
        cluster.await("/api/v1/cluster", c -> c.path("workers").size() == 3, Duration.ofMinutes(2));
    }

    @AfterAll
    void stop() throws Exception {
        if (cluster != null) {
            cluster.close();
        }
        if (mysql != null) {
            mysql.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @Order(1)
    void copiesAMillionRowsFromMySqlToPostgreSqlRowForRow() throws Exception {
        JsonNode run = cluster.post("/api/v1/runs", Map.of("definition", job("orders-to-postgres", "orders", "")));
        String id = run.path("id").asText();
        JsonNode detail = cluster.await("/api/v1/runs/" + id, Support::ended, Duration.ofMinutes(10));
        assertThat(detail.path("run").path("status").asText())
                .as("%s\n%s", detail, cluster.outputs())
                .isEqualTo("SUCCESS");

        // the same rows on both sides: counts, sums, NULLs, ranges and a sample compared value by value
        String summary = "SELECT COUNT(*), SUM(id), SUM(amount), COUNT(status), COUNT(note), MIN(created_at),"
                + " MAX(created_at), SUM(LENGTH(customer)) FROM ";
        assertThat(Support.rows(postgres(), summary + "public.orders"))
                .isEqualTo(Support.rows(mysql(), summary + "shop.orders"))
                .first()
                .satisfies(row -> assertThat(row.get(0)).isEqualTo(String.valueOf(ROWS)));
        String sample = "SELECT id, customer, amount, status, created_at, note FROM %s WHERE id %% 997 = 0 ORDER BY id";
        List<List<String>> expected = Support.rows(mysql(), sample.formatted("shop.orders"));
        assertThat(expected).hasSizeGreaterThan(1000);
        assertThat(Support.rows(postgres(), sample.formatted("public.orders"))).isEqualTo(expected);

        // the server kept the metrics, their history and the analysis of the run
        JsonNode metrics = cluster.get("/api/v1/runs/" + id + "/metrics");
        assertThat(metrics.path("rowsRead").asLong()).isEqualTo(ROWS);
        assertThat(metrics.path("rowsWritten").asLong()).isEqualTo(ROWS);
        assertThat(metrics.path("splitsFinished").asInt())
                .isEqualTo(metrics.path("splitsTotal").asInt())
                .isGreaterThanOrEqualTo(12);
        assertThat(cluster.get("/api/v1/runs/" + id + "/metrics/history").size())
                .isGreaterThan(0);
        JsonNode analysis = cluster.get("/api/v1/runs/" + id + "/analysis");
        assertThat(analysis.path("schemaVersion").asText()).isEqualTo("1");
        assertThat(analysis.path("bottleneck").path("kind").asText())
                .isIn("THROTTLE", "TARGET", "NETWORK", "SOURCE", "NONE");
        // the list shows the run with its counts
        JsonNode listed =
                cluster.get("/api/v1/runs?job=orders-to-postgres").path("runs").get(0);
        assertThat(listed.path("rowsWritten").asLong()).isEqualTo(ROWS);
    }

    @Test
    @Order(2)
    void aJobFailsAndSaysWhyWhenAWorkerIsKilled() throws Exception {
        // slow enough to be running when the worker goes
        JsonNode run = cluster.post(
                "/api/v1/runs",
                Map.of("definition", job("orders-slowly", "orders_slow", "  limit.rows-per-second: 20000\n")));
        String id = run.path("id").asText();
        JsonNode tasks = cluster.await(
                "/api/v1/runs/" + id + "/tasks",
                t -> t.size() > 0 && t.findValuesAsText("state").stream().allMatch("RUNNING"::equals),
                Duration.ofMinutes(2));
        cluster.await(
                "/api/v1/runs/" + id + "/metrics", m -> m.path("rowsWritten").asLong() > 0, Duration.ofMinutes(2));
        String workerId = tasks.get(0).path("worker").asText();
        String address = null;
        String dataAddress = null;
        for (JsonNode worker : cluster.get("/api/v1/cluster").path("workers")) {
            if (worker.path("id").asText().equals(workerId)) {
                address = worker.path("rpcAddress").asText();
                dataAddress = worker.path("dataAddress").asText();
            }
        }
        assertThat(address).as("worker %s of the task", workerId).isNotNull();
        String victim = cluster.workerAt(address);
        cluster.kill(victim);

        JsonNode detail = cluster.await("/api/v1/runs/" + id, Support::ended, Duration.ofMinutes(2));
        JsonNode error = detail.path("run").path("error");
        assertThat(detail.path("run").path("status").asText()).as("%s", detail).isEqualTo("FAILED");
        // the reason names the lost worker: no heartbeat, or the data connection to it broke
        assertThat(error.path("code").asText()).as("%s", error).isIn("PF-CLUS-0001", "PF-CLUS-0010");
        assertThat(error.path("message").asText()).as("%s", error).containsAnyOf(workerId, address, dataAddress);
        // the cluster carries on without it
        cluster.await("/api/v1/cluster", c -> c.path("workers").size() == 2, Duration.ofMinutes(1));
    }

    @Test
    @Order(3)
    void thePipeforgeCommandRunsJobsOnTheClusterAndFollowsThem() throws Exception {
        Path home = Path.of(System.getProperty("pipeforge.dist"))
                .getParent()
                .getParent()
                .resolve("e2e-run/master");
        Path file = home.getParent().resolve("cli-job.yaml");
        try (Connection connection = postgres();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.orders_cli (LIKE public.orders INCLUDING ALL)");
            statement.execute("CREATE TABLE public.orders_cancel (LIKE public.orders INCLUDING ALL)");
        }
        Files.writeString(file, job("orders-cli", "orders_cli", ""));

        Cluster.Command run = cluster.pipeforge(home, "run", file.toString(), "--analyze");
        assertThat(run.exit()).as("%s\n%s", run.out(), run.err()).isZero();
        assertThat(run.out())
                .startsWith("Job orders-cli SUCCESS in ")
                .contains("read 1,000,000 rows, wrote 1,000,000 rows");
        String id = run.err()
                .lines()
                .filter(l -> l.startsWith("Submitted run "))
                .findFirst()
                .orElseThrow()
                .split(" ")[2];

        Cluster.Command status = cluster.pipeforge(home, "run", "status", id, "-o", "json");
        assertThat(status.exit()).isZero();
        JsonNode json = Cluster.JSON.readTree(status.out());
        assertThat(json.path("run").path("status").asText()).isEqualTo("SUCCESS");
        assertThat(json.path("metrics").path("splitsFinished").asInt()).isEqualTo(12);
        assertThat(cluster.pipeforge(home, "run", "metrics", id).out()).contains("rowsWritten         1,000,000");
        assertThat(cluster.pipeforge(home, "run", "analyze", id).exit()).isZero();
        Cluster.Command logs = cluster.pipeforge(home, "run", "logs", id, "--task", "orders[0]");
        assertThat(logs.exit()).isZero();
        assertThat(logs.out()).contains(" is running").contains(" finished");

        // a slow run, detached, then cancelled: the command waits for nothing and the run ends
        Files.writeString(file, job("orders-cancel", "orders_cancel", "  limit.rows-per-second: 5000\n"));
        Cluster.Command detached = cluster.pipeforge(home, "run", "--detach", file.toString());
        assertThat(detached.exit()).as(detached.err()).isZero();
        String slow = detached.out().strip();
        cluster.await(
                "/api/v1/runs/" + slow,
                d -> "RUNNING".equals(d.path("run").path("status").asText()),
                Duration.ofMinutes(1));
        Cluster.Command cancel = cluster.pipeforge(home, "run", "cancel", slow, "--yes", "--reason", "end-to-end test");
        assertThat(cancel.exit()).as(cancel.err()).isZero();
        assertThat(cluster.await("/api/v1/runs/" + slow, Support::ended, Duration.ofMinutes(1))
                        .path("run")
                        .path("status")
                        .asText())
                .isEqualTo("CANCELLED");

        // retried by hand from the start, as its second attempt, and cancelled again
        assertThat(cluster.pipeforge(home, "run", "checkpoints", slow).out())
                .isEqualTo("Run " + slow + " has no checkpoints.\n");
        Cluster.Command retry = cluster.pipeforge(home, "run", "retry", slow, "--from-scratch");
        assertThat(retry.exit()).as(retry.err()).isZero();
        cluster.await(
                "/api/v1/runs/" + slow,
                d -> "RUNNING".equals(d.path("run").path("status").asText()),
                Duration.ofMinutes(1));
        Cluster.Command attempts = cluster.pipeforge(home, "run", "attempts", slow, "-o", "json");
        assertThat(attempts.exit()).as(attempts.err()).isZero();
        assertThat(Cluster.JSON.readTree(attempts.out()).findValuesAsText("status"))
                .containsExactly("CANCELLED", "RUNNING");
        assertThat(cluster.pipeforge(home, "run", "cancel", slow, "--yes").exit())
                .isZero();
        cluster.await("/api/v1/runs/" + slow, Support::ended, Duration.ofMinutes(1));

        // the cluster and the server's plan of a job
        Cluster.Command workers = cluster.pipeforge(home, "cluster", "workers");
        assertThat(workers.exit()).as(workers.err()).isZero();
        assertThat(workers.out().lines()).hasSize(3).first().asString().startsWith("WORKER");
        assertThat(cluster.pipeforge(home, "cluster", "status").out()).contains("Workers  2 connected");
        Cluster.Command explain = cluster.pipeforge(home, "job", "explain", file.toString(), "-o", "json");
        assertThat(explain.exit()).as(explain.err()).isZero();
        assertThat(Cluster.JSON
                        .readTree(explain.out())
                        .path("plan")
                        .path("nodes")
                        .size())
                .isEqualTo(2);

        Cluster.Command unknown = cluster.pipeforge(home, "run", "status", "no-such-run");
        assertThat(unknown.exit()).isEqualTo(6);
        assertThat(unknown.err()).startsWith("ERROR PF-CONF-0010");
    }

    String job(String name, String target, String env) {
        return """
                version: 1
                name: %s
                env:
                  parallelism: 3
                %ssource:
                  - id: orders
                    type: jdbc-mysql
                    url: %s
                    username: %s
                    password: %s
                    table: shop.orders
                    split.count: 12
                sink:
                  - id: warehouse
                    type: jdbc-postgres
                    url: %s
                    username: %s
                    password: %s
                    table: public.%s
                """
                .formatted(
                        name,
                        env,
                        mysql.getJdbcUrl(),
                        mysql.getUsername(),
                        mysql.getPassword(),
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword(),
                        target);
    }

    Connection mysql() throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    Connection postgres() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** A million orders made in MySQL itself: six tables of digits joined give the numbers. */
    void loadMySql() throws Exception {
        try (Connection connection = mysql();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE shop.digits (d INT PRIMARY KEY)");
            statement.execute("INSERT INTO shop.digits VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9)");
            statement.execute("CREATE TABLE shop.orders (id BIGINT PRIMARY KEY, customer VARCHAR(40) NOT NULL,"
                    + " amount DECIMAL(12, 2) NOT NULL, status VARCHAR(10), created_at DATETIME(3) NOT NULL,"
                    + " note TEXT)");
            statement.execute("INSERT INTO shop.orders SELECT n + 1, CONCAT('customer-', n % 9973),"
                    + " (n % 100000) / 100 + 0.01,"
                    + " CASE WHEN n % 7 = 0 THEN NULL ELSE ELT(1 + n % 3, 'NEW', 'PAID', 'SHIPPED') END,"
                    + " TIMESTAMP('2026-01-01 00:00:00.000') + INTERVAL n * 1001000 MICROSECOND,"
                    + " CASE WHEN n % 11 = 0 THEN NULL ELSE CONCAT('note ', n, ' – ünïcödé') END"
                    + " FROM (SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 + g.d * 100000 AS n"
                    + " FROM shop.digits a, shop.digits b, shop.digits c, shop.digits e, shop.digits f,"
                    + " shop.digits g) numbers");
        }
    }

    void createTargets() throws Exception {
        try (Connection connection = postgres();
                Statement statement = connection.createStatement()) {
            for (String table : List.of("orders", "orders_slow")) {
                statement.execute("CREATE TABLE public." + table + " (id BIGINT PRIMARY KEY, customer VARCHAR(40)"
                        + " NOT NULL, amount NUMERIC(12, 2) NOT NULL, status VARCHAR(10),"
                        + " created_at TIMESTAMP(3) NOT NULL, note TEXT)");
            }
        }
    }
}
