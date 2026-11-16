/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import org.devlive.pipeforge.core.plan.JobPlan;
import org.devlive.pipeforge.engine.api.JobResult;
import org.devlive.pipeforge.engine.api.JobStatus;
import org.devlive.pipeforge.engine.network.NetworkEnvironment;
import org.devlive.pipeforge.engine.testing.Probe;
import org.devlive.pipeforge.engine.testing.TestConnectors;
import org.junit.jupiter.api.Test;

/** A master and workers in one process, connected the way a cluster is. */
class LocalClusterTest {

    static JobPlan rescale(String probe) {
        return TestConnectors.plan(
                """
                version: 1
                name: rescale
                source:
                  - { id: a, type: numbers, probe: %s, rows: 3000, splits: 6, parallelism: 2 }
                sink:
                  - { id: out, type: collect, probe: %s, parallelism: 3 }
                """
                        .formatted(probe, probe));
    }

    @Test
    void threeWorkersRegisterAndExchangeRowsOverTcp() throws Exception {
        try (LocalEngine engine = new LocalEngine(3)) {
            assertThat(engine.workers()).isEqualTo(3);
            String probe = Probe.create();
            JobResult result = engine.submit(rescale(probe)).result().get(60, TimeUnit.SECONDS);
            assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(JobStatus.SUCCESS);
            assertThat(Probe.get(probe).rows).hasSize(3000);
            assertThat(Probe.get(probe).rowsBySubtask.keySet()).containsExactlyInAnyOrder(0, 1, 2);
            assertThat(engine.cluster().networks())
                    .as("tasks on different workers read each other's outputs over TCP")
                    .anySatisfy(n -> assertThat(n.transport().openConnections()).isPositive());
            assertThat(engine.cluster().networks())
                    .allSatisfy(n -> assertThat(n.address()).isNotNull());
        }
    }

    @Test
    void oneWorkerExchangesRowsInMemory() throws Exception {
        try (LocalEngine engine = new LocalEngine()) {
            assertThat(engine.workers()).isEqualTo(1);
            String probe = Probe.create();
            assertThat(engine.submit(rescale(probe))
                            .result()
                            .get(60, TimeUnit.SECONDS)
                            .status())
                    .isEqualTo(JobStatus.SUCCESS);
            NetworkEnvironment only = engine.cluster().networks().get(0);
            assertThat(only.address()).as("no port is opened").isNull();
        }
    }

    @Test
    void theNumberOfWorkersComesFromTheSystemProperty() {
        String previous = System.getProperty(LocalEngine.WORKERS);
        System.setProperty(LocalEngine.WORKERS, "2");
        try (LocalEngine engine = new LocalEngine()) {
            assertThat(engine.workers()).isEqualTo(2);
        } finally {
            if (previous == null) {
                System.clearProperty(LocalEngine.WORKERS);
            } else {
                System.setProperty(LocalEngine.WORKERS, previous);
            }
        }
        assertThatThrownBy(() -> new LocalEngine(0)).hasMessageContaining("at least one worker");
    }
}
