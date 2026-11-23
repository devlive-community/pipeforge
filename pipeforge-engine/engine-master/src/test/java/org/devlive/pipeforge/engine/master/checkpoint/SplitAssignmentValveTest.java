/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.master.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.devlive.pipeforge.engine.rpc.OperatorEvent;
import org.devlive.pipeforge.engine.rpc.SerializedValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The valve keeps every split in exactly one snapshot, however assignments and checkpoints interleave. */
class SplitAssignmentValveTest {

    /** A split event or a checkpoint trigger on its way to a reader, or in its mailbox. */
    record Item(int reader, List<String> splits, long trigger, CompletableFuture<Void> delivered) {}

    /** The enumerator, the transport, the readers and the coordinator, stepped in a random order. */
    static final class Model {
        final Random random;
        final int readers;
        final List<String> pending = new ArrayList<>();
        /** Sent, not delivered: the transport may deliver them in any order. */
        final List<Item> transit = new ArrayList<>();

        final List<ArrayDeque<Item>> mailboxes = new ArrayList<>();
        final List<Set<String>> received = new ArrayList<>();
        final SplitAssignmentValve valve;

        long checkpoint;
        boolean running;
        List<String> enumeratorState = List.of();
        final Map<Integer, Set<String>> readerStates = new HashMap<>();
        int completed;

        /** {@code false}: events go out at once and triggers right after the enumerator's snapshot. */
        final boolean withValve;

        Model(long seed, int readers, int splits, boolean withValve) {
            this.withValve = withValve;
            this.random = new Random(seed);
            this.readers = readers;
            for (int i = 0; i < splits; i++) {
                pending.add("split-" + i);
            }
            for (int r = 0; r < readers; r++) {
                mailboxes.add(new ArrayDeque<>());
                received.add(new HashSet<>());
            }
            valve = new SplitAssignmentValve((reader, event) -> {
                List<String> ids = new ArrayList<>();
                if (event instanceof OperatorEvent.AddSplits add) {
                    add.splits().forEach(v -> ids.add(new String(v.bytes(), java.nio.charset.StandardCharsets.UTF_8)));
                }
                Item item = new Item(reader, ids, 0, new CompletableFuture<>());
                transit.add(item);
                return item.delivered();
            });
        }

        void step() {
            switch (random.nextInt(4)) {
                case 0 -> assign();
                case 1 -> deliver();
                case 2 -> process();
                default -> startCheckpoint();
            }
        }

        void assign() {
            if (pending.isEmpty()) {
                return;
            }
            String split = pending.remove(random.nextInt(pending.size()));
            int reader = random.nextInt(readers);
            if (!withValve) {
                transit.add(new Item(reader, List.of(split), 0, new CompletableFuture<>()));
                return;
            }
            valve.send(
                    reader,
                    new OperatorEvent.AddSplits(
                            "src",
                            List.of(new SerializedValue(1, split.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        }

        void deliver() {
            if (transit.isEmpty()) {
                return;
            }
            Item item = transit.remove(random.nextInt(transit.size()));
            mailboxes.get(item.reader()).add(item);
            item.delivered().complete(null);
        }

        void process() {
            int reader = random.nextInt(readers);
            Item item = mailboxes.get(reader).poll();
            if (item == null) {
                return;
            }
            if (item.trigger() == 0) {
                received.get(reader).addAll(item.splits());
                return;
            }
            // the reader's snapshot, then its ack
            readerStates.put(reader, new HashSet<>(received.get(reader)));
            if (withValve) {
                valve.open(reader, item.trigger());
            }
            if (readerStates.size() == readers) {
                check();
                valve.openAll(checkpoint);
                running = false;
                completed++;
            }
        }

        void startCheckpoint() {
            if (running) {
                return;
            }
            running = true;
            long id = ++checkpoint;
            enumeratorState = List.copyOf(pending);
            readerStates.clear();
            List<Integer> all = new ArrayList<>();
            for (int r = 0; r < readers; r++) {
                all.add(r);
            }
            Runnable triggers = () -> {
                for (int r = 0; r < readers; r++) {
                    transit.add(new Item(r, List.of(), id, new CompletableFuture<>()));
                }
            };
            if (withValve) {
                valve.close(id, all).thenRun(triggers);
            } else {
                triggers.run();
            }
        }

        void check() {
            Map<String, Integer> seen = new HashMap<>();
            enumeratorState.forEach(s -> seen.merge(s, 1, Integer::sum));
            readerStates.values().forEach(state -> state.forEach(s -> seen.merge(s, 1, Integer::sum)));
            assertThat(seen.keySet())
                    .as("checkpoint %d: every split is in a snapshot", checkpoint)
                    .hasSize(splits());
            assertThat(seen)
                    .as("checkpoint %d: no split is in two", checkpoint)
                    .allSatisfy((split, count) -> assertThat(count).isOne());
        }

        int splits() {
            return pending.size() + received.stream().mapToInt(Set::size).sum() + inFlightSplits();
        }

        int inFlightSplits() {
            int count = 0;
            for (Item item : transit) {
                count += item.splits().size();
            }
            for (ArrayDeque<Item> mailbox : mailboxes) {
                for (Item item : mailbox) {
                    count += item.splits().size();
                }
            }
            for (int r = 0; r < readers; r++) {
                count += valve.held(r);
            }
            return count;
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5})
    void everySplitIsInExactlyOneSnapshot(int readers) {
        int checkpoints = 0;
        for (long seed = 0; seed < 500; seed++) {
            Model model = new Model(seed, readers, 30, true);
            for (int step = 0; step < 400; step++) {
                model.step();
            }
            checkpoints += model.completed;
        }
        assertThat(checkpoints)
                .as("enough checkpoints completed to mean something")
                .isGreaterThan(1000);
    }

    @Test
    void withoutTheValveASplitIsLostOrTakenTwice() {
        int broken = 0;
        for (long seed = 0; seed < 200; seed++) {
            Model model = new Model(seed, 2, 30, false);
            try {
                for (int step = 0; step < 400; step++) {
                    model.step();
                }
            } catch (AssertionError e) {
                broken++;
            }
        }
        assertThat(broken).as("the property test can fail").isPositive();
    }

    @Test
    void heldEventsGoOutInOrderWhenTheReaderAcknowledges() {
        List<String> sent = new ArrayList<>();
        SplitAssignmentValve valve = new SplitAssignmentValve((reader, event) -> {
            sent.add(reader + " " + event.getClass().getSimpleName());
            return new CompletableFuture<>();
        });
        valve.send(0, new OperatorEvent.NoMoreSplits("src"));
        CompletableFuture<Void> ready = valve.close(3, List.of(0, 1));
        assertThat(ready).as("an event is still on its way").isNotDone();
        valve.send(0, new OperatorEvent.AddSplits("src", List.of()));
        valve.send(0, new OperatorEvent.NoMoreSplits("src"));
        valve.send(1, new OperatorEvent.NoMoreSplits("src"));
        assertThat(valve.held(0)).isEqualTo(2);
        assertThat(sent).containsExactly("0 NoMoreSplits");

        valve.open(0, 2);
        assertThat(valve.isClosed(0))
                .as("an ack of an older checkpoint opens nothing")
                .isTrue();
        valve.open(0, 3);
        assertThat(sent).containsExactly("0 NoMoreSplits", "0 AddSplits", "0 NoMoreSplits");
        valve.openAll(3);
        assertThat(sent).endsWith("1 NoMoreSplits");
        assertThat(valve.isClosed(1)).isFalse();

        valve.close(5, List.of(0));
        valve.close(4, List.of(0));
        valve.openAll(4);
        assertThat(valve.isClosed(0)).as("still closed for checkpoint 5").isTrue();
    }
}
