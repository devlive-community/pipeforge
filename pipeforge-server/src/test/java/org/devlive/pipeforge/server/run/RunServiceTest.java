/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.server.config.ServerProperties;
import org.devlive.pipeforge.server.metastore.MetastoreDataSources;
import org.devlive.pipeforge.server.metastore.MetastoreMigrations;
import org.devlive.pipeforge.server.metastore.MetastoreVendor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The runs in the metastore, and the tracker, without a master. */
class RunServiceTest {

    final HikariDataSource data = MetastoreDataSources.create(new ServerProperties.Metastore(
            "jdbc:h2:mem:runs-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
            "sa",
            "",
            null,
            2,
            true,
            java.time.Duration.ofDays(30)));
    final RunRepository runs;
    final Instant now = Instant.parse("2026-11-17T08:00:00.123Z");
    static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    RunServiceTest() {
        new MetastoreMigrations(data, MetastoreVendor.H2, "test").migrate();
        runs = new RunRepository(data, new TransactionTemplate(new DataSourceTransactionManager(data)));
    }

    @AfterEach
    void close() {
        data.close();
    }

    @Test
    void aStateChangesOnceWhenServersRaceForIt() {
        runs.insert("r1", "job", "yaml", null, now);
        assertThat(runs.transition("r1", RunState.QUEUE, RunState.RUNNING, now.plusSeconds(1), now, null, "a"))
                .isTrue();
        assertThat(runs.transition("r1", RunState.QUEUE, RunState.RUNNING, now.plusSeconds(1), now, null, "b"))
                .as("the second server sees the run moved on")
                .isFalse();
        Runs.RunDetail detail = runs.detail("r1");
        assertThat(detail.transitions())
                .extracting(Runs.Transition::to)
                .containsExactly(RunState.QUEUE, RunState.RUNNING);
        assertThat(detail.run().startedAt()).isEqualTo(now);
        assertThat(detail.run().submittedAt()).as("UTC, to the millisecond").isEqualTo(now);
        assertThat(runs.live()).extracting(Runs.Run::id).containsExactly("r1");
        runs.transition(
                "r1",
                RunState.RUNNING,
                RunState.FAILED,
                now.plusSeconds(2),
                null,
                new Runs.RunError("PF-X", "m"),
                "PF-X");
        assertThat(runs.live()).isEmpty();
        assertThat(runs.find("r1").orElseThrow().endedAt()).isEqualTo(now.plusSeconds(2));
        assertThat(runs.list(EnumSet.of(RunState.FAILED), "job", 10, null).runs())
                .hasSize(1);
    }

    @Test
    void withoutAMasterNothingIsSubmittedAndTheTrackerWaits() {
        RunTracker tracker = new RunTracker(runs, MasterClient.none(), java.time.Clock.systemUTC(), properties(), JSON);
        runs.insert("r2", "job", "yaml", null, now);
        tracker.poll();
        assertThat(runs.find("r2").orElseThrow().status()).isEqualTo(RunState.QUEUE);
        assertThatThrownBy(() -> MasterClient.none().submit(null))
                .isInstanceOfSatisfying(
                        PipeForgeException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MASTER_NOT_CONFIGURED));
    }

    @Test
    void anUnreachableMasterLeavesRunsAsTheyAre() {
        MasterClient unreachable = new StubMaster() {
            @Override
            public String address() {
                return "master:1";
            }

            @Override
            public void submit(Submission submission) {}

            @Override
            public void cancel(String runId, String reason) {}

            @Override
            public Optional<Status> status(String runId) {
                throw PipeForgeException.of(ErrorCode.RPC_UNAVAILABLE)
                        .param("target", "master:1")
                        .param("reason", "down")
                        .build();
            }

            @Override
            public void close() {}
        };
        runs.insert("r3", "job", "yaml", null, now);
        new RunTracker(runs, unreachable, java.time.Clock.systemUTC(), properties(), JSON).poll();
        assertThat(runs.find("r3").orElseThrow().status()).isEqualTo(RunState.QUEUE);
    }

    static ServerProperties properties() {
        return new ServerProperties(
                new ServerProperties.Plugin(
                        java.util.List.of(), java.nio.file.Path.of("cache"), new ServerProperties.Dev(false, null)),
                new ServerProperties.Job("UTC", java.util.Map.of()),
                new ServerProperties.Metastore(
                        "jdbc:h2:mem:x", null, null, null, 1, true, java.time.Duration.ofDays(30)),
                new ServerProperties.Master(
                        null,
                        "",
                        new ServerProperties.Tls(false, null, "", null, ""),
                        java.time.Duration.ofMillis(50),
                        java.time.Duration.ofMillis(50)));
    }

    @Test
    void aRunTheMasterHasNotReceivedYetIsNotLost() {
        MasterClient forgetful = new StubMaster() {
            @Override
            public String address() {
                return "master:1";
            }

            @Override
            public void submit(Submission submission) {}

            @Override
            public void cancel(String runId, String reason) {}

            @Override
            public Optional<Status> status(String runId) {
                return Optional.empty();
            }

            @Override
            public void close() {}
        };
        java.time.Clock clock = java.time.Clock.fixed(now.plusSeconds(5), java.time.ZoneOffset.UTC);
        runs.insert("fresh", "job", "yaml", null, now);
        runs.insert("old", "job", "yaml", null, now.minus(RunTracker.SUBMIT_GRACE));
        runs.insert("running", "job", "yaml", null, now);
        runs.transition("running", RunState.QUEUE, RunState.RUNNING, now, now, null, null);
        new RunTracker(runs, forgetful, clock, properties(), JSON).poll();
        assertThat(runs.find("fresh").orElseThrow().status())
                .as("just submitted")
                .isEqualTo(RunState.QUEUE);
        assertThat(runs.find("old").orElseThrow().status()).isEqualTo(RunState.FAILED);
        assertThat(runs.find("running").orElseThrow().error().code()).isEqualTo(ErrorCode.RUN_LOST.id());
    }
}
