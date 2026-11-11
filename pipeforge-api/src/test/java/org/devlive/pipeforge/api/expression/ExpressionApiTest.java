/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.List;
import org.devlive.pipeforge.api.factory.FactoryContext;
import org.devlive.pipeforge.api.factory.JobContext;
import org.devlive.pipeforge.api.option.ReadonlyConfig;
import org.devlive.pipeforge.api.table.CatalogTable;
import org.devlive.pipeforge.api.transform.TransformContext;
import org.devlive.pipeforge.api.type.DataTypes;
import org.junit.jupiter.api.Test;

class ExpressionApiTest {

    @Test
    void parsesTheErrorPolicy() {
        assertThat(OnError.parse("dirty")).isEqualTo(OnError.DIRTY);
        assertThat(OnError.parse(" NULL ")).isEqualTo(OnError.NULL);
        assertThat(OnError.FAIL.value()).isEqualTo("fail");
        assertThatThrownBy(() -> OnError.parse("skip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dirty, null or fail");
    }

    @Test
    void describesAnExpression() {
        ExpressionInfo info = new ExpressionInfo("qty * 2", DataTypes.INT64(), true);
        assertThat(info.type()).isEqualTo(DataTypes.INT64());
        assertThatThrownBy(() -> new ExpressionInfo("x", null, true)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void contextsWithoutExpressionsSaySo() {
        FactoryContext factory = new FactoryContext() {
            @Override
            public String nodeName() {
                return "n";
            }

            @Override
            public ReadonlyConfig options() {
                return ReadonlyConfig.empty();
            }

            @Override
            public JobContext job() {
                return JobContext.local("j");
            }

            @Override
            public List<CatalogTable> inputTables() {
                return List.of();
            }

            @Override
            public ClassLoader classLoader() {
                return getClass().getClassLoader();
            }
        };
        TransformContext transform = new TransformContext() {
            @Override
            public JobContext job() {
                return new JobContext("j", 1, ZoneId.of("UTC"));
            }

            @Override
            public int subtaskIndex() {
                return 0;
            }

            @Override
            public int parallelism() {
                return 1;
            }

            @Override
            public List<CatalogTable> inputTables() {
                return List.of();
            }

            @Override
            public ClassLoader classLoader() {
                return getClass().getClassLoader();
            }
        };
        assertThatThrownBy(factory::expressions).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(transform::expressions).isInstanceOf(UnsupportedOperationException.class);
    }
}
