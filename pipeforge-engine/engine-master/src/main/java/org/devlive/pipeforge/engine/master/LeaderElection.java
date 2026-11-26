/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elects the leading master of a cluster through a lease in the metastore's {@code leader} table
 * (expand migration 5). The leader renews the lease every third of its length; its epoch, one more
 * with every new leader, is the fencing token that every request to a worker carries.
 *
 * <p>No clocks are compared across machines. A standby takes the lease over only after it saw the
 * row unchanged for a whole lease on its own clock; the leader counts itself the leader only until
 * two thirds of a lease after its last renewal started, so it steps down before any standby can take
 * over, even when it can no longer reach the metastore. Taking over and renewing are compare and
 * set updates on the row, so two masters never both win.
 */
public final class LeaderElection implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LeaderElection.class);

    /** Learns when this master becomes the leader and when it stops being one. */
    public interface Listener {
        /** This master leads with {@code epoch}; called before {@link #isLeader} turns true. */
        void granted(long epoch);

        /** This master no longer leads; called after {@link #isLeader} turned false. */
        void revoked();
    }

    /**
     * Who competes and for how long a lease lasts.
     *
     * @param cluster the cluster's name ({@code cluster.name}): masters of one cluster compete
     * @param holder what tells this master apart from every other, even one at the same address
     * @param address where workers and servers reach this master, {@code host:port}
     * @param lease how long a lease lasts without renewal ({@code master.leader.lease})
     */
    public record Settings(String cluster, String holder, String address, Duration lease) {
        public Settings {
            Objects.requireNonNull(cluster, "cluster");
            Objects.requireNonNull(address, "address");
            if (holder.isEmpty()) {
                throw new IllegalArgumentException("the holder of a lease must not be empty");
            }
            if (lease.compareTo(Duration.ofMillis(300)) < 0) {
                throw new IllegalArgumentException("the leader lease must be at least 300ms, not " + lease);
            }
        }

        /** How often the leader renews and a standby looks: a third of the lease. */
        public Duration interval() {
            return lease.dividedBy(3);
        }
    }

    /** The lease as the row holds it. */
    private record Row(String holder, String address, long epoch, long renewals) {}

    private final DataSource dataSource;
    private final String url;
    private final Settings settings;
    private final LongSupplier nanos;
    private final Clock clock;
    private final Listener listener;
    private volatile boolean leader;
    private volatile long epoch;
    private volatile String leaderAddress = "";
    /** When the last successful renewal (or the takeover) started, by {@link #nanos}. */
    private volatile long renewedAt;
    /** The row as last seen by a standby, and when it last changed. */
    private @Nullable Row observed;

    private long observedAt;
    private boolean closed;
    private @Nullable ScheduledFuture<?> ticks;

    /**
     * @param url the metastore's URL without credentials, for messages
     * @param nanos the monotonic clock, {@link System#nanoTime} outside tests
     */
    public LeaderElection(
            DataSource dataSource, String url, Settings settings, LongSupplier nanos, Clock clock, Listener listener) {
        this.dataSource = dataSource;
        this.url = url;
        this.settings = settings;
        this.nanos = nanos;
        this.clock = clock;
        this.listener = listener;
    }

    /**
     * Checks that the metastore has the leader table, which a server of this version creates.
     *
     * @throws PipeForgeException {@code PF-CLUS-0013} otherwise
     */
    public void check() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT COUNT(*) FROM leader WHERE leader_epoch < 0");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
        } catch (SQLException e) {
            throw unavailable(
                    "it has no leader table; start a server of this version on it first, which migrates it ("
                            + e.getMessage() + ")",
                    e);
        }
    }

    /** Competes now, then every {@link Settings#interval()} on {@code threads}. */
    public synchronized void start(ScheduledExecutorService threads) {
        tick();
        long millis = settings.interval().toMillis();
        ticks = threads.scheduleWithFixedDelay(this::tickQuietly, millis, millis, TimeUnit.MILLISECONDS);
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.warn("The leader election of master {} failed", settings.address(), e);
        }
    }

    /**
     * Whether this master leads now: it holds the lease and renewed it less than two thirds of a
     * lease ago, so that a master that stalled stops acting before a standby can take over.
     */
    public boolean isLeader() {
        return leader
                && nanos.getAsLong() - renewedAt
                        < settings.lease().minus(settings.interval()).toNanos();
    }

    /** The epoch of the current leadership; 0 before this master led. */
    public long epoch() {
        return epoch;
    }

    /** Where the leader is, as last seen; this master's own address while it leads, empty when unknown. */
    public String leaderAddress() {
        return isLeader() ? settings.address() : leaderAddress;
    }

    /**
     * Renews the lease while leading, or looks whether it can be taken; {@link #start} calls it on
     * its thread, tests with their own clock call it directly.
     */
    public synchronized void tick() {
        if (closed) {
            return;
        }
        long now = nanos.getAsLong();
        if (leader) {
            renew(now);
        } else {
            compete(now);
        }
    }

    private void renew(long started) {
        int updated;
        try {
            updated = update(
                    "UPDATE leader SET renewals = renewals + 1, renewed_at = ?"
                            + " WHERE cluster_name = ? AND holder = ? AND leader_epoch = ?",
                    timestamp(),
                    settings.cluster(),
                    settings.holder(),
                    epoch);
        } catch (SQLException e) {
            long since = nanos.getAsLong() - renewedAt;
            if (since >= settings.lease().minus(settings.interval()).toNanos()) {
                LOG.error(
                        "Master {} could not renew its lease for {} ms and steps down: {}",
                        settings.address(),
                        TimeUnit.NANOSECONDS.toMillis(since),
                        e.getMessage());
                stepDown();
            } else {
                LOG.warn("Master {} could not renew its lease: {}", settings.address(), e.getMessage());
            }
            return;
        }
        if (updated == 1) {
            renewedAt = started;
        } else {
            LOG.error("Master {} lost its lease of epoch {}: another master took it", settings.address(), epoch);
            stepDown();
        }
    }

    private void compete(long now) {
        Row row;
        try {
            row = read();
            if (row == null) {
                if (insert()) {
                    win(now, 1);
                }
                return;
            }
        } catch (SQLException e) {
            LOG.warn("Master {} cannot read the leader lease: {}", settings.address(), e.getMessage());
            return;
        }
        leaderAddress = row.holder().isEmpty() ? "" : row.address();
        if (!row.equals(observed)) {
            observed = row;
            observedAt = now;
            if (!row.holder().isEmpty()) {
                // a live lease, or one that just changed: wait a whole lease before taking it
                return;
            }
        } else if (!row.holder().isEmpty()
                && now - observedAt < settings.lease().toNanos()) {
            return;
        }
        try {
            int updated = update(
                    "UPDATE leader SET holder = ?, address = ?, leader_epoch = leader_epoch + 1,"
                            + " renewals = renewals + 1, acquired_at = ?, renewed_at = ?"
                            + " WHERE cluster_name = ? AND leader_epoch = ? AND renewals = ?",
                    settings.holder(),
                    settings.address(),
                    timestamp(),
                    timestamp(),
                    settings.cluster(),
                    row.epoch(),
                    row.renewals());
            if (updated == 1) {
                if (!row.holder().isEmpty()) {
                    LOG.warn(
                            "The lease of master {} (epoch {}) was not renewed for {} ms; master {} takes it over",
                            row.address(),
                            row.epoch(),
                            settings.lease().toMillis(),
                            settings.address());
                }
                win(now, row.epoch() + 1);
            }
        } catch (SQLException e) {
            LOG.warn("Master {} cannot take the leader lease: {}", settings.address(), e.getMessage());
        }
    }

    private void win(long now, long won) {
        renewedAt = now;
        observed = null;
        epoch = won;
        listener.granted(won);
        leader = true;
        LOG.info("Master {} leads cluster {} with epoch {}", settings.address(), settings.cluster(), won);
    }

    private void stepDown() {
        leader = false;
        leaderAddress = "";
        observed = null;
        listener.revoked();
    }

    /**
     * Stops competing and, while leading, gives the lease up, so that a standby takes over at its
     * next look instead of after a whole lease.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ScheduledFuture<?> scheduled = ticks;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        if (leader) {
            leader = false;
            try {
                update(
                        "UPDATE leader SET holder = '', renewals = renewals + 1, renewed_at = ?"
                                + " WHERE cluster_name = ? AND holder = ? AND leader_epoch = ?",
                        timestamp(),
                        settings.cluster(),
                        settings.holder(),
                        epoch);
                LOG.info("Master {} gave up its lease of epoch {}", settings.address(), epoch);
            } catch (SQLException e) {
                LOG.warn(
                        "Master {} could not give up its lease; a standby takes it over after {} ms: {}",
                        settings.address(),
                        settings.lease().toMillis(),
                        e.getMessage());
            }
            listener.revoked();
        }
    }

    private @Nullable Row read() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT holder, address, leader_epoch, renewals FROM leader WHERE cluster_name = ?")) {
            statement.setString(1, settings.cluster());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? new Row(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4))
                        : null;
            }
        }
    }

    /** Creates the row of the cluster with this master holding epoch 1; false when another did first. */
    private boolean insert() throws SQLException {
        try {
            update(
                    "INSERT INTO leader (cluster_name, holder, address, leader_epoch, renewals, acquired_at, renewed_at)"
                            + " VALUES (?, ?, ?, 1, 0, ?, ?)",
                    settings.cluster(),
                    settings.holder(),
                    settings.address(),
                    timestamp(),
                    timestamp());
            return true;
        } catch (SQLException e) {
            // a duplicate key: another master created it at the same moment; competing goes on
            if (read() != null) {
                return false;
            }
            throw e;
        }
    }

    private int update(String sql, Object... values) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement.executeUpdate();
        }
    }

    private Timestamp timestamp() {
        return Timestamp.from(clock.instant());
    }

    private PipeForgeException unavailable(String reason, Throwable cause) {
        return PipeForgeException.of(ErrorCode.METASTORE_UNAVAILABLE)
                .param("url", url)
                .param("reason", reason)
                .cause(cause)
                .build();
    }
}
