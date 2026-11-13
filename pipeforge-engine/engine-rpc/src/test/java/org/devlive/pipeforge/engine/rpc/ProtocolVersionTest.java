/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProtocolVersionTest {

    @Test
    void parsesAndPrints() {
        assertThat(ProtocolVersion.parse("1.0")).isEqualTo(ProtocolVersion.CURRENT);
        assertThat(ProtocolVersion.parse(" 2.13 ")).isEqualTo(new ProtocolVersion(2, 13));
        assertThat(new ProtocolVersion(1, 4)).hasToString("1.4");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1.", "v1.0", "1.0.0", "01.0", "1.-1", "0.9"})
    void rejectsWhatIsNotAVersion(String text) {
        assertThatThrownBy(() -> ProtocolVersion.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesTheLowerMinorVersionOfTheSameMajorVersion() {
        ProtocolVersion one = new ProtocolVersion(1, 0);
        ProtocolVersion onePointTwo = new ProtocolVersion(1, 2);
        assertThat(one.negotiate(onePointTwo)).contains(one);
        assertThat(onePointTwo.negotiate(one)).contains(one);
        assertThat(onePointTwo.negotiate(onePointTwo)).contains(onePointTwo);
    }

    @Test
    void refusesAnotherMajorVersion() {
        assertThat(new ProtocolVersion(1, 5).negotiate(new ProtocolVersion(2, 0)))
                .isEmpty();
    }
}
