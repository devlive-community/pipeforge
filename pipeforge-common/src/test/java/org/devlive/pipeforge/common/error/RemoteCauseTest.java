/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Errors keep their causes and scope when they are rebuilt in another process. */
class RemoteCauseTest {

    @Test
    void remoteCausesAreListedAsReported() {
        PipeForgeException local = PipeForgeException.of(ErrorCode.INTERNAL)
                .param("detail", "boom")
                .cause(new IllegalStateException("outer", new IOException("connection reset")))
                .build();
        List<String> reported = PipeForgeException.causeChain(local);
        assertThat(reported)
                .containsExactly("java.lang.IllegalStateException: outer", "java.io.IOException: connection reset");

        PipeForgeException rebuilt = PipeForgeException.of(ErrorCode.INTERNAL)
                .param("detail", "boom")
                .cause(RemoteCause.chain(reported))
                .build();
        assertThat(PipeForgeException.causeChain(rebuilt)).isEqualTo(reported);
        assertThat(rebuilt.toErrorInfo("en-US").causeChain()).isEqualTo(reported);
    }

    @Test
    void noCausesMakeNoChain() {
        assertThat(RemoteCause.chain(List.of())).isNull();
        RemoteCause cause = RemoteCause.chain(List.of("a: b"));
        assertThat(cause).isNotNull();
        assertThat(cause.text()).isEqualTo("a: b");
        assertThat(cause.getStackTrace()).isEmpty();
    }

    @Test
    void scopeRoundTripsThroughItsMap() {
        ErrorScope scope = ErrorScope.node("map")
                .withJob("orders")
                .withRun("r1")
                .withSubtask(2)
                .withTable("crm.users")
                .withSplit("s7")
                .withDefinition(3, 5, "transform[0].mapping");
        Map<String, String> asText = new java.util.LinkedHashMap<>();
        scope.toMap().forEach((k, v) -> asText.put(k, String.valueOf(v)));

        assertThat(ErrorScope.fromMap(asText)).isEqualTo(scope);
        assertThat(ErrorScope.fromMap(scope.toMap())).isEqualTo(scope);
        assertThat(ErrorScope.fromMap(Map.of("other", "x"))).isEqualTo(ErrorScope.none());
        assertThatThrownBy(() -> ErrorScope.fromMap(Map.of("subtask", "two")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subtask");
    }
}
