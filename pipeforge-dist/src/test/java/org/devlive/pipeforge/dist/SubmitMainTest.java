/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.devlive.pipeforge.engine.rpc.proto.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubmitMainTest {

    @TempDir
    Path home;

    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    int run(Map<String, String> environment, String... args) {
        return SubmitMain.run(
                args,
                environment,
                home,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void refusesWrongArgumentsAndUnreachableMasters() throws Exception {
        Map<String, String> token = Map.of("PIPEFORGE_CLUSTER_TOKEN", "t");
        assertThat(run(token)).isEqualTo(2);
        assertThat(run(token, "a.yaml", "b.yaml")).isEqualTo(2);
        assertThat(run(token, "--bogus")).isEqualTo(2);
        assertThat(run(token, "a.yaml", "--timeout")).isEqualTo(2);
        assertThat(run(token, "a.yaml", "--timeout", "soon")).isEqualTo(2);
        assertThat(run(token, home.resolve("missing.yaml").toString())).isEqualTo(2);
        Path job = Files.writeString(home.resolve("job.yaml"), "version: 1\n");
        assertThat(run(Map.of(), job.toString())).as("no token").isEqualTo(2);
        assertThat(run(token, job.toString(), "--master", "localhost:" + ProcessTest.freePort()))
                .isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("usage: <job.yaml>")
                .contains("unknown argument 'b.yaml'")
                .contains("unknown argument '--bogus'")
                .contains("'--timeout' needs a value")
                .contains("is not a duration")
                .contains("cannot read")
                .contains("PF-CONF-0011")
                .contains("PF-CLUS-0008");
        assertThat(SubmitMain.name(RunState.RUN_STATE_SUCCESS)).isEqualTo("SUCCESS");
    }
}
