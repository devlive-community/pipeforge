/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The leader lease on the metastore's leader table, on H2; the server tests run it on every database. */
class LeaderElectionTest {

    static final Duration LEASE = Duration.ofSeconds(15);
    static final long THIRD = LEASE.dividedBy(3).toNanos();

    final AtomicLong clock = new AtomicLong(1);
    final AtomicBoolean down = new AtomicBoolean();
    String url;
    DataSource database;

    /** Connections to an in-memory H2 database, or a failure while {@link #down} is set. */
    @BeforeEach
    void create() throws Exception {
        url = "jdbc:h2:mem:leader-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            String script = Files.readString(Path.of(
                    "..",
                    "..",
                    "pipeforge-server",
                    "src",
                    "main",
                    "resources",
                    "db",
                    "metastore",
                    "expand",
                    "h2",
                    "V5__leader.sql"));
            statement.execute(script.replaceAll("(?m)^--.*$", ""));
        }
        database = (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        if (down.get()) {
                            throw new SQLException("the metastore is down");
                        }
                        return DriverManager.getConnection(url, "sa", "");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Records what an election tells its master. */
    static final class Events implements LeaderElection.Listener {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public void granted(long epoch) {
            seen.add("granted " + epoch);
        }

        @Override
        public void revoked() {
            seen.add("revoked");
        }
    }

    LeaderElection election(String name, Events events) {
        return election("default", name, events);
    }

    LeaderElection election(String cluster, String name, Events events) {
        return new LeaderElection(
                database,
                "jdbc:h2:mem:test",
                new LeaderElection.Settings(cluster, name + "-id", name + ":6123", LEASE),
                clock::get,
                Clock.systemUTC(),
                events);
    }

    void advance(long nanos) {
        clock.addAndGet(nanos);
    }

    @Test
    void oneOfTwoMastersLeadsAndTheOtherKnowsWhere() {
        Events a = new Events();
        Events b = new Events();
        LeaderElection first = election("a", a);
        LeaderElection second = election("b", b);
        first.check();
        first.tick();
        second.tick();

        assertThat(first.isLeader()).isTrue();
        assertThat(first.epoch()).isEqualTo(1);
        assertThat(first.leaderAddress()).isEqualTo("a:6123");
        assertThat(second.isLeader()).isFalse();
        assertThat(second.leaderAddress()).isEqualTo("a:6123");
        assertThat(a.seen).containsExactly("granted 1");
        assertThat(b.seen).isEmpty();

        // while the leader renews, the standby never takes over
        for (int i = 0; i < 10; i++) {
            advance(THIRD);
            first.tick();
            second.tick();
        }
        assertThat(first.isLeader()).isTrue();
        assertThat(second.isLeader()).isFalse();

        // masters of another cluster do not compete with these
        LeaderElection other = election("east", "c", new Events());
        other.tick();
        assertThat(other.isLeader()).isTrue();
        assertThat(other.epoch()).isEqualTo(1);
    }

    @Test
    void aStandbyTakesOverALeaseThatWasNotRenewedForAWholeLease() {
        Events a = new Events();
        Events b = new Events();
        LeaderElection first = election("a", a);
        LeaderElection second = election("b", b);
        first.tick();
        second.tick();

        // the leader stops renewing, as a master that died
        advance(LEASE.toNanos() - 1);
        second.tick();
        assertThat(second.isLeader()).as("not before a whole lease").isFalse();
        assertThat(first.isLeader())
                .as("the stalled leader stops acting on its own")
                .isFalse();
        advance(1);
        second.tick();
        assertThat(second.isLeader()).isTrue();
        assertThat(second.epoch()).as("the fencing token grows").isEqualTo(2);
        assertThat(b.seen).containsExactly("granted 2");

        // the old leader comes back: its renewal finds the lease taken
        first.tick();
        assertThat(first.isLeader()).isFalse();
        assertThat(first.leaderAddress()).isEmpty();
        assertThat(a.seen).containsExactly("granted 1", "revoked");
        first.tick();
        assertThat(first.leaderAddress()).isEqualTo("b:6123");
    }

    @Test
    void aLeaderThatCannotReachTheMetastoreStepsDownBeforeAStandbyCanTakeOver() {
        Events a = new Events();
        LeaderElection first = election("a", a);
        first.tick();

        down.set(true);
        advance(THIRD);
        first.tick();
        assertThat(first.isLeader()).as("one failed renewal is tolerated").isTrue();
        advance(THIRD);
        first.tick();
        assertThat(first.isLeader()).isFalse();
        assertThat(a.seen).containsExactly("granted 1", "revoked");

        // back again: it waits a whole lease like any standby, then leads with a new epoch
        down.set(false);
        first.tick();
        assertThat(first.isLeader()).isFalse();
        advance(LEASE.toNanos());
        first.tick();
        assertThat(first.isLeader()).isTrue();
        assertThat(first.epoch()).isEqualTo(2);
    }

    @Test
    void aLeaseGivenUpIsTakenAtTheNextLook() {
        Events a = new Events();
        LeaderElection first = election("a", a);
        LeaderElection second = election("b", new Events());
        first.tick();
        second.tick();

        first.close();
        assertThat(first.isLeader()).isFalse();
        assertThat(a.seen).containsExactly("granted 1", "revoked");
        first.tick();
        assertThat(first.isLeader()).as("a closed election no longer competes").isFalse();

        advance(THIRD);
        second.tick();
        assertThat(second.isLeader()).isTrue();
        assertThat(second.epoch()).isEqualTo(2);
        second.close();
        second.close();
    }

    @Test
    void competesOnItsOwnThreadAndChecksTheTable() throws Exception {
        var threads = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try (LeaderElection real = new LeaderElection(
                database,
                "jdbc:h2:mem:test",
                new LeaderElection.Settings("default", "r-id", "r:6123", Duration.ofMillis(300)),
                System::nanoTime,
                Clock.systemUTC(),
                new Events())) {
            real.start(threads);
            assertThat(real.isLeader()).as("leads once started").isTrue();
            Thread.sleep(500);
            assertThat(real.isLeader()).as("renews on its own").isTrue();
        } finally {
            threads.shutdownNow();
            threads.awaitTermination(5, TimeUnit.SECONDS);
        }

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE leader");
        }
        assertThatThrownBy(() -> election("a", new Events()).check())
                .hasMessageContaining("PF-CLUS-0013")
                .hasMessageContaining("has no leader table");
        assertThatThrownBy(() -> new LeaderElection.Settings("default", "", "a:1", LEASE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeaderElection.Settings("default", "x", "a:1", Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
