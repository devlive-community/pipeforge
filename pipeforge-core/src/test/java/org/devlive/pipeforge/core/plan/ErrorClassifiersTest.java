/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.devlive.pipeforge.api.capability.ErrorClassifier;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.junit.jupiter.api.Test;

class ErrorClassifiersTest {

    private final PipeForgeException lost = PipeForgeException.of(ErrorCode.CONN_LOST)
            .param("target", "db")
            .param("reason", "reset")
            .build();

    @Test
    void theFirstClassifierThatKnowsTheErrorWins() {
        ErrorClassifier unknown = error -> null;
        ErrorClassifier known = error -> lost;
        ErrorClassifier other = error -> {
            throw new AssertionError("not asked");
        };

        assertThat(ErrorClassifiers.classify(new IOException("x"), "not a classifier", null, unknown, known, other))
                .isSameAs(lost);
        assertThat(ErrorClassifiers.classify(new IOException("x"), unknown, null))
                .isNull();
        assertThat(ErrorClassifiers.classify(new IOException("x"))).isNull();
    }

    @Test
    void aClassifierThatThrowsIsSkippedAndKeptAsSuppressed() {
        IOException error = new IOException("x");
        ErrorClassifier broken = e -> {
            throw new IllegalStateException("broken");
        };

        assertThat(ErrorClassifiers.classify(error, broken, (ErrorClassifier) e -> lost))
                .isSameAs(lost);
        assertThat(error.getSuppressed()).singleElement().satisfies(e -> assertThat(e)
                .hasMessage("broken"));

        IllegalStateException itself = new IllegalStateException("itself");
        ErrorClassifier rethrows = e -> {
            throw (IllegalStateException) e;
        };
        assertThat(ErrorClassifiers.classify(itself, rethrows)).isNull();
        assertThat(itself.getSuppressed()).isEmpty();
    }
}
