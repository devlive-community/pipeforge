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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The M1 acceptance benchmark: {@code rows} orders of 20 columns (about 300 bytes a row) copied
 * from MySQL to PostgreSQL by a cluster of the packaged distribution with three workers. It
 * reconciles both sides, follows the run's metrics on the server while it runs, and writes a
 * report in JSON and Markdown. Correctness checks fail the benchmark; the throughput target only
 * when {@code enforce-target} is set, since it holds for the benchmark environment of the
 * performance design, not for any machine.
 *
 * <p>Runs with {@code -Pbenchmark}; settings are system properties {@code pipeforge.benchmark.*},
 * see development/benchmarks in the documentation. Without external databases, MySQL and
 * PostgreSQL run in Docker.
 */
@DisabledOnOs(OS.WINDOWS)
class AcceptanceBenchmark {

    /** The throughput the performance design asks of MySQL → PostgreSQL on 8 slots, rows per second. */
    static final long TARGET_ROWS_PER_SECOND = 300_000;

    /** Rows per insert when the dataset is made; one statement per chunk keeps transactions small. */
    static final long CHUNK = 1_000_000;

    static String setting(String key, String fallback) {
        String value = System.getProperty("pipeforge.benchmark." + key, "");
        return value.isBlank() ? fallback : value;
    }

    final long rows = Long.parseLong(setting("rows", "100000000"));
    final int parallelism = Integer.parseInt(setting("parallelism", "8"));
    final int slots = Integer.parseInt(setting("slots-per-worker", "4"));
    final int splits = Integer.parseInt(setting("split-count", String.valueOf(parallelism * 4)));
    final boolean enforceTarget = Boolean.parseBoolean(setting("enforce-target", "false"));
    final Path reports = Path.of(setting("report-dir", "target/benchmark"));

    String mysqlUrl;
    String mysqlUser;
    String mysqlPassword;
    String postgresUrl;
    String postgresUser;
    String postgresPassword;
    String mysqlVersion;
    String postgresVersion;

    @Test
    void copiesMySqlToPostgreSqlOnThreeWorkers() throws Exception {
        MySQLContainer mysql = null;
        PostgreSQLContainer postgres = null;
        Path dist = Path.of(System.getProperty("pipeforge.dist"));
        Path dir = dist.getParent().getParent().resolve("benchmark-run");
        try {
            mysqlUrl = setting("mysql.url", "");
            postgresUrl = setting("postgres.url", "");
            if (mysqlUrl.isEmpty() || postgresUrl.isEmpty()) {
                assumeTrue(
                        DockerClientFactory.instance().isDockerAvailable(),
                        "Docker, or pipeforge.benchmark.mysql.url and .postgres.url, is needed");
            }
            if (mysqlUrl.isEmpty()) {
                mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4")).withDatabaseName("bench");
                mysql.start();
                mysqlUrl = mysql.getJdbcUrl();
                mysqlUser = mysql.getUsername();
                mysqlPassword = mysql.getPassword();
            } else {
                mysqlUser = setting("mysql.user", "root");
                mysqlPassword = setting("mysql.password", "");
            }
            if (postgresUrl.isEmpty()) {
                postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
                postgres.start();
                postgresUrl = postgres.getJdbcUrl();
                postgresUser = postgres.getUsername();
                postgresPassword = postgres.getPassword();
            } else {
                postgresUser = setting("postgres.user", "postgres");
                postgresPassword = setting("postgres.password", "");
            }

            Instant loadStart = Instant.now();
            boolean reused = prepareSource();
            Duration load = Duration.between(loadStart, Instant.now());
            prepareTarget();

            Support.deleteTree(dir);
            Files.createDirectories(dir);
            try (Cluster cluster = new Cluster(dist, dir)) {
                Path master = cluster.startMaster(Duration.ofSeconds(30));
                Support.installDriverPlugin(master.resolve("plugins"));
                for (int i = 1; i <= 3; i++) {
                    cluster.startWorker("worker-" + i, slots, "zone=" + (char) ('a' + i - 1));
                }
                cluster.startServer(master);
                cluster.await("/api/v1/cluster", c -> c.path("workers").size() == 3, Duration.ofMinutes(2));
                runAndReport(cluster, load, reused);
            }
        } finally {
            if (mysql != null) {
                mysql.stop();
            }
            if (postgres != null) {
                postgres.stop();
            }
        }
    }

    void runAndReport(Cluster cluster, Duration load, boolean reused) throws Exception {
        String job =
                """
                version: 1
                name: m1-benchmark
                env:
                  parallelism: %d
                source:
                  - id: orders
                    type: jdbc-mysql
                    url: %s
                    username: %s
                    password: %s
                    table: bench.orders
                    split.count: %d
                    fetch-size: 5000
                sink:
                  - id: warehouse
                    type: jdbc-postgres
                    url: %s
                    username: %s
                    password: %s
                    table: public.orders
                """
                        .formatted(
                                parallelism,
                                mysqlUrl,
                                mysqlUser,
                                mysqlPassword,
                                splits,
                                postgresUrl,
                                postgresUser,
                                postgresPassword);
        String id = cluster.post("/api/v1/runs", Map.of("definition", job))
                .path("id")
                .asText();

        // follow the run as the Web UI does: its metrics and split progress every few seconds
        ArrayNode timeline = Cluster.JSON.createArrayNode();
        long started = System.nanoTime();
        JsonNode detail;
        for (; ; ) {
            detail = cluster.get("/api/v1/runs/" + id);
            if (Support.ended(detail)) {
                break;
            }
            if ("RUNNING".equals(detail.path("run").path("status").asText())) {
                JsonNode metrics = cluster.get("/api/v1/runs/" + id + "/metrics");
                ObjectNode sample = timeline.addObject();
                sample.put("seconds", (System.nanoTime() - started) / 1_000_000_000.0);
                sample.put("rowsRead", metrics.path("rowsRead").asLong());
                sample.put("rowsWritten", metrics.path("rowsWritten").asLong());
                sample.put(
                        "readRowsPerSecond", metrics.path("readRowsPerSecond").asDouble());
                sample.put(
                        "writeRowsPerSecond", metrics.path("writeRowsPerSecond").asDouble());
                sample.put("splitsFinished", metrics.path("splitsFinished").asInt());
                sample.put("splitsTotal", metrics.path("splitsTotal").asInt());
            }
            Thread.sleep(5_000);
        }
        JsonNode run = detail.path("run");
        JsonNode metrics = cluster.get("/api/v1/runs/" + id + "/metrics");
        JsonNode analysis = cluster.get("/api/v1/runs/" + id + "/analysis");
        double seconds = Duration.between(
                                Instant.parse(run.path("startedAt").asText()),
                                Instant.parse(run.path("endedAt").asText()))
                        .toMillis()
                / 1000.0;
        double throughput = rows / Math.max(seconds, 0.001);

        String summary =
                "SELECT COUNT(*), SUM(id), SUM(amount), SUM(quantity), COUNT(note), MAX(updated_at)" + " FROM ";
        List<List<String>> source = Support.rows(mysql(), summary + "bench.orders");
        List<List<String>> target = Support.rows(postgres(), summary + "public.orders");

        List<Check> checks = new ArrayList<>();
        checks.add(new Check(
                "the run succeeded",
                "SUCCESS".equals(run.path("status").asText()),
                run.path("status").asText()
                        + (run.path("error").isMissingNode()
                                        || run.path("error").isNull()
                                ? ""
                                : " " + run.path("error")),
                false));
        checks.add(new Check(
                "PostgreSQL has every row of MySQL, the same sums and NULL counts",
                source.equals(target),
                "MySQL " + source + ", PostgreSQL " + target,
                false));
        checks.add(new Check(
                "the server counts every row read and written",
                metrics.path("rowsRead").asLong() == rows
                        && metrics.path("rowsWritten").asLong() == rows,
                metrics.path("rowsRead").asLong() + " read, "
                        + metrics.path("rowsWritten").asLong() + " written",
                false));
        checks.add(new Check(
                "every split was read",
                metrics.path("splitsTotal").asInt() >= splits
                        && metrics.path("splitsFinished").asInt()
                                == metrics.path("splitsTotal").asInt(),
                metrics.path("splitsFinished").asInt() + " of "
                        + metrics.path("splitsTotal").asInt(),
                false));
        checks.add(splitProgress(timeline, rows, parallelism));
        checks.add(rateAgreement(timeline, throughput));
        String kind = analysis.path("bottleneck").path("kind").asText();
        checks.add(new Check(
                "the run analysis concludes a bottleneck",
                !kind.isEmpty() && !"UNKNOWN".equals(kind),
                kind
                        + (analysis.path("bottleneck").path("node").isTextual()
                                ? " at "
                                        + analysis.path("bottleneck")
                                                .path("node")
                                                .asText()
                                : ""),
                false));
        checks.add(new Check(
                "throughput of at least " + TARGET_ROWS_PER_SECOND + " rows/s (benchmark environment)",
                throughput >= TARGET_ROWS_PER_SECOND,
                String.format(Locale.ROOT, "%.0f rows/s", throughput),
                !enforceTarget));

        ObjectNode report = report(load, reused, seconds, throughput, metrics, analysis, timeline, checks);
        Path written = write(report);
        System.out.println("Benchmark report: " + written);
        assertThat(checks).as("checks of the benchmark; report %s", written).allSatisfy(check -> assertThat(
                        check.passed() || check.informational())
                .as("%s: %s", check.name(), check.detail())
                .isTrue());
    }

    /**
     * A check of the report.
     *
     * @param informational reported, but does not fail the benchmark
     */
    record Check(String name, boolean passed, String detail, boolean informational) {}

    /**
     * Split progress matches the run: the total is known from the first sample, the splits read
     * only grow, and their share stays with the share of rows read. The source splits on ranges of
     * equal size, so at most one split per reader is being read: the share of splits read lies
     * between the share of rows read less (parallelism + 1) splits and that share plus one split.
     */
    static Check splitProgress(ArrayNode timeline, long rows, int parallelism) {
        int last = -1;
        int total = -1;
        List<String> problems = new ArrayList<>();
        for (JsonNode sample : timeline) {
            int finished = sample.path("splitsFinished").asInt();
            int splits = sample.path("splitsTotal").asInt();
            double seconds = sample.path("seconds").asDouble();
            if (total < 0) {
                total = splits;
            } else if (splits != total) {
                problems.add(String.format(Locale.ROOT, "%.0f s: total %d, first %d", seconds, splits, total));
            }
            if (finished < last) {
                problems.add(String.format(Locale.ROOT, "%.0f s: %d read after %d", seconds, finished, last));
            }
            last = finished;
            if (splits > 0) {
                double share = (double) finished / splits;
                double read = (double) sample.path("rowsRead").asLong() / rows;
                if (share > read + 1.0 / splits || share < read - (parallelism + 1.0) / splits) {
                    problems.add(String.format(
                            Locale.ROOT,
                            "%.0f s: %d of %d splits read with %.0f%% of the rows",
                            seconds,
                            finished,
                            splits,
                            read * 100));
                }
            }
        }
        return new Check(
                "split progress follows the rows read",
                problems.isEmpty(),
                problems.isEmpty()
                        ? timeline.size() + " sample(s), " + total + " split(s) from the first"
                        : String.join("; ", problems),
                false);
    }

    /**
     * The read rates the server reported while the run ran agree with the measured throughput, within
     * 30%; the first and last samples, when readers start and finish, are left out.
     */
    static Check rateAgreement(ArrayNode timeline, double throughput) {
        List<Double> rates = new ArrayList<>();
        for (int i = 1; i < timeline.size() - 1; i++) {
            rates.add(timeline.get(i).path("readRowsPerSecond").asDouble());
        }
        if (rates.size() < 3) {
            return new Check(
                    "reported rates agree with the measured throughput",
                    true,
                    "too short to compare: " + timeline.size() + " sample(s)",
                    true);
        }
        double mean = rates.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double deviation = Math.abs(mean - throughput) / throughput;
        return new Check(
                "reported rates agree with the measured throughput",
                deviation <= 0.3,
                String.format(
                        Locale.ROOT,
                        "mean reported %.0f rows/s, measured %.0f rows/s (%.0f%% apart)",
                        mean,
                        throughput,
                        deviation * 100),
                false);
    }

    Connection mysql() throws Exception {
        return DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPassword);
    }

    Connection postgres() throws Exception {
        return DriverManager.getConnection(postgresUrl, postgresUser, postgresPassword);
    }

    /**
     * Makes {@code bench.orders} with {@code rows} rows, or keeps it when it has them already, since
     * making a hundred million rows takes a while. Returns whether it was kept.
     */
    boolean prepareSource() throws Exception {
        try (Connection connection = mysql();
                Statement statement = connection.createStatement()) {
            mysqlVersion = connection.getMetaData().getDatabaseProductVersion();
            statement.execute("CREATE DATABASE IF NOT EXISTS bench");
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'bench' AND table_name = 'orders'")) {
                result.next();
                if (result.getLong(1) == 1) {
                    try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM bench.orders")) {
                        count.next();
                        if (count.getLong(1) == rows) {
                            return true;
                        }
                    }
                }
            }
            statement.execute("DROP TABLE IF EXISTS bench.orders");
            statement.execute("DROP TABLE IF EXISTS bench.digits");
            statement.execute("CREATE TABLE bench.digits (d INT PRIMARY KEY)");
            statement.execute("INSERT INTO bench.digits VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9)");
            statement.execute("CREATE TABLE bench.orders (" + COLUMNS_MYSQL + ")");
            for (long base = 0; base < rows; base += CHUNK) {
                long size = Math.min(CHUNK, rows - base);
                statement.execute("INSERT INTO bench.orders SELECT " + GENERATED.replace("{n}", "(" + base + " + n)")
                        + " FROM (SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 + g.d * 100000 AS n"
                        + " FROM bench.digits a, bench.digits b, bench.digits c, bench.digits e,"
                        + " bench.digits f, bench.digits g) numbers WHERE n < " + size);
            }
            return false;
        }
    }

    void prepareTarget() throws Exception {
        try (Connection connection = postgres();
                Statement statement = connection.createStatement()) {
            postgresVersion = connection.getMetaData().getDatabaseProductVersion();
            statement.execute("DROP TABLE IF EXISTS public.orders");
            statement.execute("CREATE TABLE public.orders (" + COLUMNS_POSTGRES + ")");
        }
    }

    /** Twenty columns of an order; with the generated values a row is about 300 bytes. */
    static final String COLUMNS_MYSQL = "id BIGINT PRIMARY KEY, order_no VARCHAR(32) NOT NULL,"
            + " customer_id BIGINT NOT NULL, customer_name VARCHAR(64) NOT NULL, email VARCHAR(80) NOT NULL,"
            + " phone VARCHAR(20), country CHAR(2) NOT NULL, city VARCHAR(40) NOT NULL,"
            + " address VARCHAR(120) NOT NULL, status VARCHAR(12) NOT NULL, currency CHAR(3) NOT NULL,"
            + " amount DECIMAL(14, 2) NOT NULL, discount DECIMAL(5, 2), quantity INT NOT NULL, weight DOUBLE,"
            + " gift TINYINT NOT NULL, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,"
            + " ship_date DATE, note VARCHAR(255)";

    static final String COLUMNS_POSTGRES = "id BIGINT PRIMARY KEY, order_no VARCHAR(32) NOT NULL,"
            + " customer_id BIGINT NOT NULL, customer_name VARCHAR(64) NOT NULL, email VARCHAR(80) NOT NULL,"
            + " phone VARCHAR(20), country CHAR(2) NOT NULL, city VARCHAR(40) NOT NULL,"
            + " address VARCHAR(120) NOT NULL, status VARCHAR(12) NOT NULL, currency CHAR(3) NOT NULL,"
            + " amount NUMERIC(14, 2) NOT NULL, discount NUMERIC(5, 2), quantity INT NOT NULL,"
            + " weight DOUBLE PRECISION, gift SMALLINT NOT NULL, created_at TIMESTAMP(3) NOT NULL,"
            + " updated_at TIMESTAMP(3) NOT NULL, ship_date DATE, note VARCHAR(255)";

    /** The values of row {@code {n}}, from 0: deterministic, with NULLs, so runs compare. */
    static final String GENERATED = "{n} + 1, CONCAT('ORD-', LPAD({n}, 12, '0')), {n} % 2000003,"
            + " CONCAT('Customer ', {n} % 2000003), CONCAT('customer', {n} % 2000003, '@example.com'),"
            + " CASE WHEN {n} % 5 = 0 THEN NULL ELSE CONCAT('+1-555-', LPAD({n} % 10000000, 7, '0')) END,"
            + " ELT(1 + {n} % 6, 'US', 'CN', 'DE', 'JP', 'BR', 'FR'),"
            + " ELT(1 + {n} % 6, 'New York', 'Shanghai', 'Berlin', 'Tokyo', 'São Paulo', 'Paris'),"
            + " CONCAT({n} % 9999 + 1, ' Market Street, Building ', {n} % 97, ', Floor ', {n} % 30),"
            + " ELT(1 + {n} % 4, 'NEW', 'PAID', 'SHIPPED', 'DELIVERED'), ELT(1 + {n} % 3, 'USD', 'CNY', 'EUR'),"
            + " ({n} % 1000000) / 100 + 0.99, CASE WHEN {n} % 3 = 0 THEN NULL ELSE ({n} % 50) / 2 END,"
            + " 1 + {n} % 20, CASE WHEN {n} % 7 = 0 THEN NULL ELSE ({n} % 10000) / 8 END, {n} % 2,"
            + " TIMESTAMP('2026-01-01 00:00:00.000') + INTERVAL {n} * 31000 MICROSECOND,"
            + " TIMESTAMP('2026-01-01 00:00:00.000') + INTERVAL {n} * 37000 MICROSECOND,"
            + " CASE WHEN {n} % 4 = 0 THEN NULL ELSE DATE('2026-01-01') + INTERVAL {n} % 365 DAY END,"
            + " CASE WHEN {n} % 3 = 0 THEN NULL ELSE CONCAT('Leave at the front desk; order ', {n},"
            + " ' – call before delivery') END";

    ObjectNode report(
            Duration load,
            boolean reused,
            double seconds,
            double throughput,
            JsonNode metrics,
            JsonNode analysis,
            ArrayNode timeline,
            List<Check> checks) {
        ObjectNode report = Cluster.JSON.createObjectNode();
        report.put("benchmark", "m1-mysql-to-postgresql");
        report.put("schemaVersion", "1");
        report.put("at", Instant.now().toString());
        report.put("passed", checks.stream().allMatch(c -> c.passed() || c.informational()));
        ObjectNode environment = report.putObject("environment");
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("cpus", Runtime.getRuntime().availableProcessors());
        environment.put(
                "memoryBytes",
                ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                        .getTotalMemorySize());
        environment.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        environment.put("commit", setting("commit", "unknown"));
        environment.put("mysql", mysqlVersion);
        environment.put("postgresql", postgresVersion);
        environment.put("databases", setting("mysql.url", "").isEmpty() ? "docker on this host" : "external");
        ObjectNode settings = report.putObject("settings");
        settings.put("rows", rows);
        settings.put("columns", 20);
        settings.put("workers", 3);
        settings.put("slotsPerWorker", slots);
        settings.put("parallelism", parallelism);
        settings.put("splits", splits);
        ObjectNode result = report.putObject("result");
        result.put("datasetLoadSeconds", load.toMillis() / 1000.0);
        result.put("datasetReused", reused);
        result.put("runSeconds", seconds);
        result.put("rowsPerSecond", throughput);
        result.put("rowsPerSecondPerSlot", throughput / parallelism);
        result.set("metrics", metrics);
        result.set("bottleneck", analysis.path("bottleneck"));
        report.set("timeline", timeline);
        ArrayNode list = report.putArray("checks");
        for (Check check : checks) {
            ObjectNode node = list.addObject();
            node.put("name", check.name());
            node.put("passed", check.passed());
            node.put("informational", check.informational());
            node.put("detail", check.detail());
        }
        return report;
    }

    /** Writes the report as JSON and Markdown, named after the time, and as latest.json and latest.md. */
    Path write(ObjectNode report) throws Exception {
        Files.createDirectories(reports);
        String name = "m1-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.parse(report.path("at").asText()));
        String json =
                Cluster.JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(report) + "\n";
        String markdown = BenchmarkReport.markdown(report);
        Files.writeString(reports.resolve(name + ".json"), json, StandardCharsets.UTF_8);
        Files.writeString(reports.resolve(name + ".md"), markdown, StandardCharsets.UTF_8);
        Files.writeString(reports.resolve("latest.json"), json, StandardCharsets.UTF_8);
        Files.writeString(reports.resolve("latest.md"), markdown, StandardCharsets.UTF_8);
        return reports.resolve(name + ".md").toAbsolutePath();
    }
}
