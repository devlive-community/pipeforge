/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.devlive.pipeforge.engine.network.Exchange.codec;
import static org.devlive.pipeforge.engine.network.Exchange.partition;
import static org.devlive.pipeforge.engine.network.Exchange.row;

import java.nio.ByteBuffer;
import org.devlive.pipeforge.engine.network.NetworkEvent.CheckpointBarrier;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.devlive.pipeforge.engine.network.partition.ResultPartition;
import org.devlive.pipeforge.engine.network.partition.SubpartitionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The checkpoint barrier event: its bytes, and its place in a stream that waits for credit. */
class CheckpointBarrierTest {

    final NetworkEnvironment worker = new NetworkEnvironment("worker-a", "token", Exchange.options());

    @AfterEach
    void stop() {
        worker.close();
    }

    static NetworkEvent decode(byte... bytes) {
        return NetworkEvent.fromBuffer(Buffer.unpooled(Buffer.Kind.EVENT, bytes));
    }

    @Test
    void hasTheLayoutOfAppendixB() {
        CheckpointBarrier barrier =
                new CheckpointBarrier(0x0102030405060708L, 1_763_798_400_000L, CheckpointBarrier.Type.SAVEPOINT, true);
        byte[] expected = ByteBuffer.allocate(19)
                .put((byte) 0x10)
                .putLong(0x0102030405060708L)
                .putLong(1_763_798_400_000L)
                .put((byte) 2)
                .put((byte) 1)
                .array();
        Buffer buffer = NetworkEvent.toBuffer(barrier);
        byte[] actual = new byte[buffer.content().remaining()];
        buffer.content().get(actual);
        assertThat(actual).isEqualTo(expected);
        assertThat(buffer.isEvent()).isTrue();
        assertThat(NetworkEvent.fromBuffer(NetworkEvent.toBuffer(barrier))).isEqualTo(barrier);

        CheckpointBarrier plain = new CheckpointBarrier(7, 0, CheckpointBarrier.Type.CHECKPOINT, false);
        assertThat(NetworkEvent.fromBuffer(NetworkEvent.toBuffer(plain))).isEqualTo(plain);
    }

    @Test
    void malformedBarriersAreRejected() {
        byte[] good = ByteBuffer.allocate(19)
                .put((byte) 0x10)
                .putLong(3)
                .putLong(0)
                .put((byte) 1)
                .put((byte) 0)
                .array();
        assertThat(decode(good)).isEqualTo(new CheckpointBarrier(3, 0, CheckpointBarrier.Type.CHECKPOINT, false));

        byte[] shortOne = java.util.Arrays.copyOf(good, 18);
        assertThatThrownBy(() -> decode(shortOne)).hasMessage("a checkpoint barrier of 17 bytes, not 18");
        byte[] type = good.clone();
        type[17] = 3;
        assertThatThrownBy(() -> decode(type)).hasMessage("a checkpoint barrier of unknown type 3");
        byte[] flags = good.clone();
        flags[18] = 0x02;
        assertThatThrownBy(() -> decode(flags)).hasMessage("a checkpoint barrier with unknown flags 0x2");
        byte[] zero = good.clone();
        java.util.Arrays.fill(zero, 1, 9, (byte) 0);
        assertThatThrownBy(() -> decode(zero)).hasMessage("a checkpoint barrier for checkpoint 0");
    }

    @Test
    void aBarrierNeedsNoCreditButNeverOvertakesTheRowsBeforeIt() throws Exception {
        ResultPartition output = worker.createPartition(partition("source"), 1, codec());
        output.emit(0, row(1), null, () -> false);
        output.emitEvent(0, new CheckpointBarrier(1, 0, CheckpointBarrier.Type.CHECKPOINT, false));
        output.emit(0, row(2), null, () -> false);
        output.flush();
        SubpartitionView view = output.createView(0, () -> {});

        assertThat(view.poll(false))
                .as("the row before the barrier waits for credit")
                .isNull();
        SubpartitionView.Delivery first = view.poll(true);
        assertThat(first).isNotNull();
        assertThat(first.buffer().isEvent()).isFalse();
        first.buffer().recycle();

        SubpartitionView.Delivery barrier = view.poll(false);
        assertThat(barrier).as("the barrier goes without credit").isNotNull();
        assertThat(NetworkEvent.fromBuffer(barrier.buffer()))
                .isEqualTo(new CheckpointBarrier(1, 0, CheckpointBarrier.Type.CHECKPOINT, false));
        barrier.buffer().recycle();

        assertThat(view.poll(false))
                .as("the row after it waits for credit again")
                .isNull();
        SubpartitionView.Delivery second = view.poll(true);
        assertThat(second).isNotNull();
        assertThat(second.buffer().isEvent()).isFalse();
        second.buffer().recycle();
        view.release();
    }
}
