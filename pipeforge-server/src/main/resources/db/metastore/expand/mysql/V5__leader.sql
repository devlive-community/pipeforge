--
-- Copyright (c) 2026 devlive-community/pipeforge
--
-- Licensed under the MIT License. See the LICENSE file in the
-- project root for full license text.
--
-- Expand migration 5: the lease of the leading master of every cluster. The masters of a cluster
-- compete for its row; leader_epoch grows by one with every new leader and fences requests of the
-- ones before, renewals grows with every renewal so that a standby sees the lease is alive. An
-- empty holder is a lease that was given up. Only additions: the previous version ignores it.
-- Times are UTC and only shown, never compared.

CREATE TABLE leader (
    cluster_name VARCHAR(128) NOT NULL,
    holder       VARCHAR(255) NOT NULL,
    address      VARCHAR(255) NOT NULL,
    leader_epoch BIGINT       NOT NULL,
    renewals     BIGINT       NOT NULL,
    acquired_at  DATETIME(3)  NOT NULL,
    renewed_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (cluster_name)
);
