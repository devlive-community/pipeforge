/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void definesCodesWithCategoryAndParameters() {
        ErrorCode code = ErrorCode.define("PF-SINK-K17-0001", "STREAM_LOAD_FAILED", "label");
        assertThat(code.id()).isEqualTo("PF-SINK-K17-0001");
        assertThat(code.name()).isEqualTo("STREAM_LOAD_FAILED");
        assertThat(code.category()).isEqualTo(ErrorCategory.SINK);
        assertThat(code.params()).containsExactly("label");
        assertThat(code).hasToString("PF-SINK-K17-0001 STREAM_LOAD_FAILED");
    }

    @Test
    void comparesByIdentifier() {
        assertThat(ErrorCode.define("PF-CONF-0001", "OPTION_REQUIRED", "option", "node"))
                .isEqualTo(ErrorCode.OPTION_REQUIRED)
                .hasSameHashCodeAs(ErrorCode.OPTION_REQUIRED)
                .isNotEqualTo(ErrorCode.OPTION_INVALID)
                .isNotEqualTo("PF-CONF-0001");
    }

    @Test
    void rejectsMalformedDefinitions() {
        assertThatThrownBy(() -> ErrorCode.define("PF-CONF-1", "X")).hasMessageContaining("Malformed error code");
        assertThatThrownBy(() -> ErrorCode.define("PF-NOPE-0001", "X")).hasMessageContaining("Unknown error category");
        assertThatThrownBy(() -> ErrorCode.define("PF-CONF-0001", "lower")).hasMessageContaining("upper snake case");
        assertThatThrownBy(() -> ErrorCode.define("PF-CONF-0001", "X", "a", "a"))
                .hasMessageContaining("Duplicate");
    }

    @Test
    void categoriesHaveDefaultKindsAndRegistryFiles() {
        assertThat(ErrorCategory.CONN.defaultKind()).isEqualTo(ErrorKind.TRANSIENT);
        assertThat(ErrorCategory.DATA.defaultKind()).isEqualTo(ErrorKind.DATA);
        assertThat(ErrorCategory.SYS.defaultKind()).isEqualTo(ErrorKind.FATAL);
        assertThat(ErrorCategory.CKPT.registryResource()).isEqualTo("errors/ckpt.yaml");
    }

    @Test
    void scopesListOnlyTheirParts() {
        ErrorScope scope = ErrorScope.none()
                .withJob("j")
                .withRun("r")
                .withNode("n")
                .withSubtask(2)
                .withTable("db.t")
                .withSplit("s-1");
        assertThat(scope.toMap())
                .containsExactly(
                        Map.entry("job", "j"),
                        Map.entry("run", "r"),
                        Map.entry("node", "n"),
                        Map.entry("subtask", 2),
                        Map.entry("table", "db.t"),
                        Map.entry("split", "s-1"));
        assertThat(ErrorScope.none().toMap()).isEmpty();
    }

    @Test
    void templatesFindAndReplacePlaceholders() {
        assertThat(MessageTemplate.placeholders("{a} and {b}, again {a}, not {1} or { c }"))
                .containsExactly("a", "b");
        assertThat(MessageTemplate.render("{a} costs $1 {missing}", Map.of("a", "x")))
                .isEqualTo("x costs $1 {missing}");
        assertThat(MessageTemplate.render("${{v}:-d}", Map.of("v", "name"))).isEqualTo("${name:-d}");
    }
}
