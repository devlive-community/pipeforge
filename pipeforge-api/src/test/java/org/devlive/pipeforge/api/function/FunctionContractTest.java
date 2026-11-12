/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FunctionContractTest {

    static final class Sample implements FunctionLifecycle {
        @ScalarFunction(name = "with_rate")
        @Returns("DECIMAL(18, 2)")
        public static BigDecimal withRate(@Param("DECIMAL(16, 2)") BigDecimal amount, int rate) {
            return amount.multiply(BigDecimal.valueOf(rate));
        }

        @ScalarFunction(name = "noise", deterministic = false, nullOnNull = false)
        public Double noise(Long seed) {
            return seed == null ? null : Math.random();
        }
    }

    @Test
    void annotationsAreReadableAtRunTimeWithTheirDefaults() throws Exception {
        Method withRate = Sample.class.getMethod("withRate", BigDecimal.class, int.class);
        ScalarFunction function = withRate.getAnnotation(ScalarFunction.class);
        assertThat(function.name()).isEqualTo("with_rate");
        assertThat(function.deterministic()).isTrue();
        assertThat(function.nullOnNull()).isTrue();
        assertThat(withRate.getAnnotation(Returns.class).value()).isEqualTo("DECIMAL(18, 2)");
        assertThat(withRate.getParameters()[0].getAnnotation(Param.class).value())
                .isEqualTo("DECIMAL(16, 2)");

        ScalarFunction noise = Sample.class.getMethod("noise", Long.class).getAnnotation(ScalarFunction.class);
        assertThat(noise.deterministic()).isFalse();
        assertThat(noise.nullOnNull()).isFalse();
    }

    @Test
    void theLifecycleDoesNothingByDefault() {
        FunctionLifecycle lifecycle = new Sample();
        assertThatCode(() -> {
                    lifecycle.open(null);
                    lifecycle.close();
                })
                .doesNotThrowAnyException();
    }
}
