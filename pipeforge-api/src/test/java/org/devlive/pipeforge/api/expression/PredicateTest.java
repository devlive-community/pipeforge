/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.expression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.devlive.pipeforge.api.expression.Predicate.And;
import org.devlive.pipeforge.api.expression.Predicate.Comparison;
import org.devlive.pipeforge.api.expression.Predicate.In;
import org.devlive.pipeforge.api.expression.Predicate.IsNull;
import org.devlive.pipeforge.api.expression.Predicate.Not;
import org.devlive.pipeforge.api.expression.Predicate.Operator;
import org.devlive.pipeforge.api.expression.Predicate.Or;
import org.junit.jupiter.api.Test;

class PredicateTest {

    @Test
    void collectsColumnsInOrderOfFirstUse() {
        Predicate predicate = new And(List.of(
                new Comparison("amount", Operator.GT, 10),
                new Or(List.of(new IsNull("note", false), new Not(new In("status", Arrays.asList("NEW", null))))),
                new Comparison("amount", Operator.LE, 100)));
        assertThat(predicate.columns()).containsExactly("amount", "note", "status");
        assertThat(Operator.NE.symbol()).isEqualTo("<>");
    }
}
