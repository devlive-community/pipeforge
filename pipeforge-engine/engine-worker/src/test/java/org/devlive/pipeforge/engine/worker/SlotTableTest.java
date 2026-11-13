/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.SlotRequest;
import org.junit.jupiter.api.Test;

class SlotTableTest {

    final AtomicLong now = new AtomicLong();
    final SlotTable table = new SlotTable("worker-1:6124", 2, now::get);

    static SlotRequest request(String allocation, int slot) {
        return new SlotRequest("job", "run", allocation, slot);
    }

    @Test
    void reservesOnceAndRefusesOthers() {
        table.reserve(request("a", 0));
        table.reserve(request("a", 0));
        assertThat(table.snapshot())
                .containsExactly(new SlotTable.Slot(0, "job", "a"), new SlotTable.Slot(1, null, null));
        assertThat(table.snapshot().get(0).allocated()).isTrue();

        assertThatThrownBy(() -> table.reserve(request("b", 0))).isInstanceOfSatisfying(PipeForgeException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.SLOT_TAKEN);
            assertThat(e.getMessage()).contains("Slot 0 of worker worker-1:6124 is reserved by allocation a");
        });
        assertThatThrownBy(() -> table.reserve(request("c", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has 2 slot(s)");
        assertThatThrownBy(() -> new SlotTable("w", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void releasesReservationsThatNoTaskUsed() {
        table.reserve(request("unused", 0));
        table.reserve(request("used", 1));
        table.use("used");

        now.addAndGet(Duration.ofSeconds(59).toNanos());
        assertThat(table.expire()).isEmpty();
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(table.expire()).containsExactly(request("unused", 0));
        assertThat(table.snapshot().get(0).allocated()).isFalse();
        assertThat(table.snapshot().get(1).allocated()).as("a task runs in it").isTrue();
    }

    @Test
    void freesAndRefusesUnknownAllocations() {
        table.reserve(request("a", 1));
        assertThat(table.free("unknown")).isFalse();
        assertThat(table.free("a")).isTrue();
        assertThat(table.snapshot()).allMatch(s -> !s.allocated());
        assertThatThrownBy(() -> table.use("a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No slot of worker worker-1:6124 is reserved for allocation a");
        assertThat(table.size()).isEqualTo(2);
    }
}
