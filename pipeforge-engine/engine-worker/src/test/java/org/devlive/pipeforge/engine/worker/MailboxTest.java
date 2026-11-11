/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MailboxTest {

    @Test
    void runsMailInOrderOnTheDrainingThread() throws Exception {
        Mailbox mailbox = new Mailbox();
        List<String> ran = new ArrayList<>();
        mailbox.put(() -> ran.add("a " + Thread.currentThread().getName()));
        mailbox.put(() -> ran.add("b"));

        assertThat(mailbox.isEmpty()).isFalse();
        assertThat(mailbox.drain()).isEqualTo(2);
        assertThat(ran).containsExactly("a " + Thread.currentThread().getName(), "b");
        assertThat(mailbox.isEmpty()).isTrue();
        assertThat(mailbox.drain()).isZero();
    }

    @Test
    void waitingReturnsAsSoonAsMailArrives() throws Exception {
        Mailbox mailbox = new Mailbox();
        List<String> ran = new ArrayList<>();
        Thread sender = new Thread(() -> {
            mailbox.put(() -> ran.add("first"));
            mailbox.put(() -> ran.add("second"));
        });
        long start = System.nanoTime();
        sender.start();
        sender.join();

        assertThat(mailbox.waitAndDrain(TimeUnit.SECONDS.toNanos(10))).isEqualTo(2);
        assertThat(ran).containsExactly("first", "second");
        assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void waitingGivesUpAfterTheTimeout() throws Exception {
        assertThat(new Mailbox().waitAndDrain(TimeUnit.MILLISECONDS.toNanos(5))).isZero();
    }
}
