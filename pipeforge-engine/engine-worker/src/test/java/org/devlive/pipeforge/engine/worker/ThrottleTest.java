/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ThrottleTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void readsAtTheQuotaWithoutBursts() {
        Throttle throttle = new Throttle();
        assertThat(throttle.waitNanos(0)).as("no quota: no limit").isZero();
        throttle.took(1_000);

        throttle.quota(100, 0, 0);
        // the bucket starts empty: the first row waits for one token
        throttle.took(10);
        assertThat(throttle.waitNanos(0)).isBetween(SECOND / 100, SECOND / 100 + 1);
        assertThat(throttle.waitNanos(SECOND / 100)).isZero();

        // an idle second fills the bucket only to 5 rows (50 ms of the quota), not 100
        long now = 2 * SECOND;
        assertThat(throttle.waitNanos(now)).isZero();
        for (int i = 0; i < 5; i++) {
            throttle.took(10);
        }
        assertThat(throttle.waitNanos(now)).isZero();
        throttle.took(10);
        assertThat(throttle.waitNanos(now)).isBetween(SECOND / 100, SECOND / 100 + 1);
    }

    @Test
    void bytesAndRowsBothLimit() {
        Throttle throttle = new Throttle();
        throttle.quota(1_000, 10_000, 0);
        throttle.took(2_000);
        // one row is a millisecond of the row quota, 2,000 bytes are 200 ms of the byte quota
        assertThat(throttle.waitNanos(0)).isBetween(200_000_000L, 200_000_001L);
        throttle.quota(0, 0, 0);
        assertThat(throttle.waitNanos(0)).as("lifted").isZero();
        assertThat(throttle.limited()).isFalse();
    }
}
