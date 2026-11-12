/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.connector.blackhole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ThrottleTest {

    /** A clock that only moves when the throttle sleeps or the test says so. */
    private long now = 1_000_000_000L;

    private final List<Long> sleeps = new ArrayList<>();

    private Throttle throttle(double rowsPerSecond) {
        return new Throttle(rowsPerSecond, () -> now, nanos -> {
            sleeps.add(nanos);
            now += nanos;
        });
    }

    @Test
    void spacesRowsEvenly() throws Exception {
        Throttle throttle = throttle(10);
        for (int i = 0; i < 5; i++) {
            throttle.acquire();
        }
        long ms = TimeUnit.MILLISECONDS.toNanos(1);
        assertThat(sleeps).containsExactly(100 * ms, 100 * ms, 100 * ms, 100 * ms);
        assertThat(now - 1_000_000_000L).isEqualTo(400 * ms);
    }

    @Test
    void catchesUpWithATenthOfASecondAtMostAfterAPause() throws Exception {
        Throttle throttle = throttle(1000);
        throttle.acquire();
        now += TimeUnit.SECONDS.toNanos(5);
        int immediate = 0;
        while (true) {
            int before = sleeps.size();
            throttle.acquire();
            if (sleeps.size() > before) {
                break;
            }
            immediate++;
        }
        // A tenth of a second at 1,000 rows per second, plus the row that is due now.
        assertThat(immediate).isEqualTo(101);
    }

    @Test
    void writesThatComeSlowerThanTheRateNeverWait() throws Exception {
        Throttle throttle = throttle(10);
        for (int i = 0; i < 5; i++) {
            throttle.acquire();
            now += TimeUnit.MILLISECONDS.toNanos(150);
        }
        assertThat(sleeps).isEmpty();
    }

    @Test
    void theRateIsAtMostOneRowPerNanosecond() throws Exception {
        Throttle throttle = throttle(1e12);
        throttle.acquire();
        throttle.acquire();
        assertThat(sleeps).containsExactly(1L);
    }

    @Test
    void anInterruptedWaitStopsTheWrite() {
        Throttle throttle = new Throttle(1, () -> 0L, nanos -> {
            throw new InterruptedException("cancelled");
        });
        assertThatThrownBy(() -> {
                    throttle.acquire();
                    throttle.acquire();
                })
                .isInstanceOf(InterruptedException.class);
    }

    @Test
    void withoutALimitNothingWaits() throws Exception {
        for (int i = 0; i < 1000; i++) {
            Throttle.NONE.acquire();
        }
        assertThat(sleeps).isEmpty();
        Throttle.system(1_000_000).acquire();
    }
}
