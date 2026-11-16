/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.ExecutionAttemptId;
import org.devlive.pipeforge.engine.rpc.LogChunk;
import org.devlive.pipeforge.engine.rpc.LogQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class LogServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(LogServiceTest.class);
    private static final ExecutionAttemptId TASK = new ExecutionAttemptId("run-1", "src", 2, 0);

    @TempDir
    Path dir;

    private final List<LogService> services = new ArrayList<>();

    private LogService service(long maxFileBytes, int maxFiles) {
        LogService service =
                new LogService("worker-1", new LogService.Settings(dir, maxFileBytes, maxFiles, Duration.ofDays(7)));
        services.add(service);
        return service;
    }

    @AfterEach
    void close() {
        services.forEach(LogService::close);
    }

    private static String entry(int i, String level) {
        return "2026-11-16 10:00:00.000 " + String.format("%-5s", level) + " [task-src-2] x.Y - line " + i + "\n";
    }

    private static void write(LogService.Scope scope, String text) {
        LogService.append(scope.context().get(LogService.MDC_LOG), text.getBytes(StandardCharsets.UTF_8));
    }

    private static LogChunk read(LogService service, LogQuery query) {
        List<LogChunk> chunks = new ArrayList<>();
        service.read(TASK, query, chunks::add).join();
        assertThat(chunks).hasSize(1);
        return chunks.get(0);
    }

    private static PipeForgeException failure(CompletableFuture<?> future) {
        Throwable error = future.handle((v, e) -> e).join();
        assertThat(error).isInstanceOf(PipeForgeException.class);
        return (PipeForgeException) error;
    }

    @Test
    void theThreadsOfATaskLogIntoItsOwnFile() throws Exception {
        LogService service = service(1024 * 1024, 2);
        Path file = service.path("orders copy.yaml", TASK);
        assertThat(file)
                .isEqualTo(dir.resolve("orders_copy.yaml").resolve("run-1").resolve("src-2-0.log"));

        LogService.Scope scope = service.open("orders copy.yaml", TASK);
        assertThat(MDC.get(LogService.MDC_TASK)).isEqualTo("run-1/src/2/0");
        assertThat(MDC.get(LogService.MDC_JOB)).isEqualTo("orders copy.yaml");
        LOG.info("from the task thread");
        Thread helper = new Thread(() -> {
            scope.enter();
            LOG.warn("from a thread of the task");
        });
        helper.start();
        helper.join();
        Thread other = new Thread(() -> LOG.info("from another thread"));
        other.start();
        other.join();
        scope.close();
        assertThat(MDC.get(LogService.MDC_TASK)).isNull();
        LOG.info("after the task");

        String text = Files.readString(file);
        assertThat(text)
                .contains("INFO  [main] o.d.p.e.worker.log.LogServiceTest - from the task thread")
                .contains("WARN  [Thread-")
                .contains("from a thread of the task")
                .doesNotContain("from another thread")
                .doesNotContain("after the task");
        assertThat(text.lines()).hasSize(2);
    }

    @Test
    void readsPagesFromTheStartAndFromTheEnd() {
        LogService service = service(1024 * 1024, 2);
        try (LogService.Scope scope = service.open("job", TASK)) {
            for (int i = 0; i < 10; i++) {
                write(scope, entry(i, "INFO"));
            }
            long size = entry(0, "INFO").length();

            LogChunk first = read(service, new LogQuery(0, 3 * size + 5, "", null, false));
            assertThat(first.text()).isEqualTo(entry(0, "INFO") + entry(1, "INFO") + entry(2, "INFO"));
            assertThat(first.offset()).as("whole lines only").isEqualTo(3 * size);
            assertThat(first.eof()).as("the task still runs").isFalse();

            LogChunk next = read(service, new LogQuery(first.offset(), 2 * size, "", null, false));
            assertThat(next.text()).isEqualTo(entry(3, "INFO") + entry(4, "INFO"));

            LogChunk tail = read(service, new LogQuery(-(2 * size + 7), 0, "", null, false));
            assertThat(tail.text()).as("from the next whole line").isEqualTo(entry(8, "INFO") + entry(9, "INFO"));
            assertThat(tail.offset()).isEqualTo(10 * size);
            assertThat(read(service, new LogQuery(10 * size + 99, 0, "", null, false))
                            .text())
                    .isEmpty();
        }
        LogChunk whole = read(service, LogQuery.all());
        assertThat(whole.text().lines()).hasSize(10);
        assertThat(whole.eof()).as("the task ended").isTrue();
    }

    @Test
    void filtersWholeEntriesByTextAndLevel() {
        LogService service = service(1024 * 1024, 2);
        try (LogService.Scope scope = service.open("job", TASK)) {
            write(scope, entry(0, "DEBUG"));
            write(scope, entry(1, "INFO"));
            write(scope, entry(2, "ERROR") + "java.lang.IllegalStateException: boom\n\tat x.Y.z(Y.java:1)\n");
            write(scope, entry(3, "WARN"));
            write(scope, entry(4, "INFO").replace("line 4", "line 4 boom"));
        }
        assertThat(read(service, new LogQuery(0, 0, "", "warn", false)).text())
                .isEqualTo(entry(2, "ERROR") + "java.lang.IllegalStateException: boom\n\tat x.Y.z(Y.java:1)\n"
                        + entry(3, "WARN"));
        assertThat(read(service, new LogQuery(0, 0, "boom", null, false)).text())
                .as("an entry matches with any of its lines")
                .isEqualTo(entry(2, "ERROR") + "java.lang.IllegalStateException: boom\n\tat x.Y.z(Y.java:1)\n"
                        + entry(4, "INFO").replace("line 4", "line 4 boom"));
        assertThat(read(service, new LogQuery(0, 0, "boom", "INFO", false))
                        .text()
                        .lines())
                .hasSize(4);
        assertThatThrownBy(() -> new LogQuery(0, 0, "", "LOUD", false))
                .isInstanceOf(PipeForgeException.class)
                .satisfies(e -> assertThat(((PipeForgeException) e).code()).isEqualTo(ErrorCode.REQUEST_INVALID));
    }

    @Test
    void aFilteredPageEndsBeforeAnEntryItCannotSeeWhole() {
        LogService service = service(1024 * 1024, 2);
        String error = entry(1, "ERROR") + "\tat x.Y.z(Y.java:1)\n\tat x.Y.w(Y.java:2)\n";
        try (LogService.Scope scope = service.open("job", TASK)) {
            write(scope, entry(0, "INFO"));
            write(scope, error);
            write(scope, entry(2, "INFO"));
            // a page that ends inside the stack trace of the error
            LogChunk page = read(
                    service,
                    new LogQuery(
                            0, entry(0, "INFO").length() + entry(1, "ERROR").length() + 5, "", "ERROR", false));
            assertThat(page.text()).isEmpty();
            assertThat(page.offset()).isEqualTo(entry(0, "INFO").length());
            LogChunk rest = read(service, new LogQuery(page.offset(), 0, "", "ERROR", false));
            assertThat(rest.text()).isEqualTo(error);
        }
    }

    @Test
    void rollsFullFilesWithoutChangingOffsets() throws Exception {
        LogService service = service(1024, 3);
        // two-digit numbers, so that every line has the same length
        int lines = 90;
        long size = entry(10, "INFO").length();
        try (LogService.Scope scope = service.open("job", TASK)) {
            for (int i = 10; i < 10 + lines; i++) {
                write(scope, entry(i, "INFO"));
            }
        }
        Path file = service.path("job", TASK);
        List<String> names;
        try (Stream<Path> files = Files.list(file.getParent())) {
            names = files.map(f -> f.getFileName().toString()).sorted().toList();
        }
        assertThat(names).hasSize(3).contains("src-2-0.log");
        assertThat(names).allSatisfy(name -> assertThat(name).matches("src-2-0\\.log(\\.\\d+)?"));
        assertThat(Files.size(file)).isLessThanOrEqualTo(1024);

        // the oldest files are gone: reading from 0 starts at the oldest one left
        List<String> seen = new ArrayList<>();
        long offset = 0;
        LogChunk chunk;
        do {
            chunk = read(service, new LogQuery(offset, 300, "", null, false));
            seen.addAll(chunk.text().lines().toList());
            offset = chunk.offset();
        } while (!chunk.eof());
        assertThat(offset).as("offsets count the whole log").isEqualTo(lines * size);
        int firstKept = 10 + lines - seen.size();
        assertThat(firstKept).isGreaterThan(10);
        for (int i = 0; i < seen.size(); i++) {
            assertThat(seen.get(i)).endsWith("line " + (firstKept + i));
        }
        String rolled =
                names.stream().filter(n -> !n.equals("src-2-0.log")).findFirst().orElseThrow();
        long start = Long.parseLong(rolled.substring("src-2-0.log.".length()));
        assertThat(read(service, new LogQuery(start, size, "", null, false)).text())
                .as("a rolled file starts at its offset")
                .isEqualTo(entry(10 + (int) (start / size), "INFO"));
    }

    @Test
    void followsATaskUntilItsLogCloses() throws Exception {
        LogService service = service(1024, 3);
        LogService.Scope scope = service.open("job", TASK);
        write(scope, entry(0, "INFO"));
        List<LogChunk> chunks = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> following = service.read(TASK, new LogQuery(0, 200, "", null, true), chunks::add);
        StringBuilder expected = new StringBuilder(entry(0, "INFO"));
        for (int i = 1; i < 40; i++) {
            write(scope, entry(i, "INFO"));
            expected.append(entry(i, "INFO"));
            if (i % 10 == 0) {
                Thread.sleep(50);
            }
        }
        assertThat(following).isNotDone();
        scope.close();
        following.get(10, TimeUnit.SECONDS);

        StringBuilder received = new StringBuilder();
        chunks.forEach(c -> received.append(c.text()));
        assertThat(received.toString())
                .as("every line once, in order, across rolled files")
                .isEqualTo(expected.toString());
        assertThat(chunks).last().satisfies(c -> assertThat(c.eof()).isTrue());
        assertThat(chunks.subList(0, chunks.size() - 1))
                .allSatisfy(c -> assertThat(c.eof()).isFalse());
    }

    @Test
    void aReaderStopsFollowingByCancelling() throws Exception {
        LogService service = service(1024, 3);
        try (LogService.Scope scope = service.open("job", TASK)) {
            List<LogChunk> chunks = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> following = service.read(TASK, new LogQuery(0, 0, "", null, true), chunks::add);
            write(scope, entry(0, "INFO"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (chunks.isEmpty()) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(5);
            }
            following.cancel(false);
            write(scope, entry(1, "INFO"));
            Thread.sleep(300);
            assertThat(chunks).hasSize(1);
        }
    }

    @Test
    void logsStayReadableForANewServiceAndUnknownTasksFail() {
        try (LogService.Scope scope = service(1024 * 1024, 2).open("job", TASK)) {
            write(scope, entry(0, "INFO"));
        }
        LogService restarted = service(1024 * 1024, 2);
        LogChunk chunk = read(restarted, LogQuery.all());
        assertThat(chunk.text()).isEqualTo(entry(0, "INFO"));
        assertThat(chunk.eof()).isTrue();

        PipeForgeException missing =
                failure(restarted.read(new ExecutionAttemptId("run-1", "src", 3, 0), LogQuery.all(), c -> {}));
        assertThat(missing.code()).isEqualTo(ErrorCode.TASK_LOG_NOT_FOUND);
        assertThat(missing.params()).containsEntry("worker", "worker-1").containsEntry("task", "run-1/src/3/0");
    }

    @Test
    void cleanupRemovesRunsPastTheRetentionButNotOpenOnes() throws Exception {
        LogService service = service(1024 * 1024, 2);
        ExecutionAttemptId old = new ExecutionAttemptId("run-old", "src", 0, 0);
        ExecutionAttemptId running = new ExecutionAttemptId("run-now", "src", 0, 0);
        try (LogService.Scope scope = service.open("old-job", old)) {
            write(scope, entry(0, "INFO"));
        }
        LogService.Scope open = service.open("busy-job", running);
        write(open, entry(0, "INFO"));
        Instant now = Instant.now();
        FileTime longAgo = FileTime.from(now.minus(Duration.ofDays(8)));
        Files.setLastModifiedTime(service.path("old-job", old), longAgo);
        Files.setLastModifiedTime(service.path("busy-job", running), longAgo);

        assertThat(service.cleanup(now)).isOne();
        assertThat(dir.resolve("old-job")).doesNotExist();
        assertThat(service.path("busy-job", running)).exists();

        open.close();
        Files.setLastModifiedTime(service.path("busy-job", running), longAgo);
        assertThat(service.cleanup(now)).isOne();
        assertThat(dir).isEmptyDirectory();
        assertThat(failure(service.read(old, LogQuery.all(), c -> {})).code()).isEqualTo(ErrorCode.TASK_LOG_NOT_FOUND);
    }
}
