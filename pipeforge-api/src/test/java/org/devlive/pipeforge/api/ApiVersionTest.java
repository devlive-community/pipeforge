/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiVersionTest {

    @Test
    void readsTheBuildVersion() {
        assertThat(ApiVersion.current()).matches("\\d+\\.\\d+\\.\\d+.*");
        assertThat(ApiVersion.descriptorForm()).isEqualTo(ApiVersion.major() + ".x");
    }
}
