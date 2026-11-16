/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.buffer.Buffer;
import org.junit.jupiter.api.Test;

/** The frames of appendix B, written and read back. */
class FrameCodecTest {

    static final UUID CHANNEL = new UUID(0x0102030405060708L, 0x090a0b0c0d0e0f10L);

    static ByteBuf encode(Frame frame) {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameCodec.Encoder());
        channel.writeOutbound(frame);
        ByteBuf out = channel.readOutbound();
        channel.finishAndReleaseAll();
        return out;
    }

    static List<Object> decode(ByteBuf bytes, int max) {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameCodec.Decoder(max));
        try {
            channel.writeInbound(bytes);
            List<Object> frames = new java.util.ArrayList<>();
            Object frame;
            while ((frame = channel.readInbound()) != null) {
                frames.add(frame);
            }
            return frames;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    static String hex(ByteBuf bytes) {
        return ByteBufUtil.hexDump(bytes);
    }

    @Test
    void everyFrameReadsBackTheSame() {
        ResultPartitionId id = new ResultPartitionId("run-1", "vertex-2", 3, 1, 0);
        List<Frame> frames = List.of(
                new Frame.Hello("1.0", "worker-1", "s3cr3t"),
                new Frame.PartitionRequest(CHANNEL, id, 7, 2),
                new Frame.AddCredit(CHANNEL, 300),
                new Frame.CancelPartition(CHANNEL),
                new Frame.CloseRequest(),
                new Frame.ErrorResponse(CHANNEL, "PF-CLUS-0011", "not there"),
                new Frame.ErrorResponse(null, "PF-CLUS-0005", "wrong token"),
                new Frame.Ping(),
                new Frame.Pong());
        for (Frame frame : frames) {
            List<Object> decoded = decode(encode(frame), 1 << 20);
            assertThat(decoded).as("%s", frame).containsExactly(frame);
        }
        Frame.BufferResponse data = new Frame.BufferResponse(
                CHANNEL, 1L << 40, 5, Buffer.Kind.EVENT, 0, Unpooled.wrappedBuffer(new byte[] {1, 2, 3}));
        Frame.BufferResponse read =
                (Frame.BufferResponse) decode(encode(data), 1 << 20).get(0);
        assertThat(read.sequence()).isEqualTo(1L << 40);
        assertThat(read.backlog()).isEqualTo(5);
        assertThat(read.kind()).isEqualTo(Buffer.Kind.EVENT);
        assertThat(ByteBufUtil.getBytes(read.data())).containsExactly(1, 2, 3);
        read.data().release();
    }

    @Test
    void writesTheBytesOfAppendixB() {
        // magic "PF", version 1, type 0x03, length 18, the channel, credit 300 as a varint
        assertThat(hex(encode(new Frame.AddCredit(CHANNEL, 300))))
                .isEqualTo("50460103" + "00000012" + "0102030405060708090a0b0c0d0e0f10" + "ac02");
        assertThat(hex(encode(new Frame.Ping()))).isEqualTo("5046010700000000");
        // hasChannel 0, code, message
        assertThat(hex(encode(new Frame.ErrorResponse(null, "PF-X", "m"))))
                .isEqualTo("50460106" + "00000008" + "00" + "04" + hex("PF-X") + "01" + hex("m"));
        // channel, sequence 0, backlog 2, DATA, no flags, the rows
        assertThat(hex(encode(new Frame.BufferResponse(
                        CHANNEL, 0, 2, Buffer.Kind.DATA, 0, Unpooled.wrappedBuffer(new byte[] {(byte) 0xAB})))))
                .isEqualTo("50460102" + "00000015" + "0102030405060708090a0b0c0d0e0f10" + "00" + "02" + "00" + "00"
                        + "ab");
    }

    static String hex(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void framesMayArriveInPieces() {
        ByteBuf all = Unpooled.buffer();
        all.writeBytes(encode(new Frame.Hello("1.0", "w", "t")));
        all.writeBytes(encode(new Frame.AddCredit(CHANNEL, 1)));
        EmbeddedChannel channel = new EmbeddedChannel(new FrameCodec.Decoder(1024));
        while (all.isReadable()) {
            channel.writeInbound(all.readRetainedSlice(1));
        }
        all.release();
        assertThat((Object) channel.readInbound()).isEqualTo(new Frame.Hello("1.0", "w", "t"));
        assertThat((Object) channel.readInbound()).isEqualTo(new Frame.AddCredit(CHANNEL, 1));
        assertThat((Object) channel.readInbound()).isNull();
        channel.finishAndReleaseAll();
    }

    static PipeForgeException refusal(String hexBytes, int max) {
        ByteBuf bytes = Unpooled.wrappedBuffer(HexFormat.of().parseHex(hexBytes));
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> decode(bytes, max));
        assertThat(thrown).as(hexBytes).isInstanceOf(DecoderException.class);
        PipeForgeException error = Handshake.protocolError(thrown);
        assertThat(error).as(hexBytes).isNotNull();
        return error;
    }

    @Test
    void refusesFramesThatBreakTheProtocol() {
        assertThat(refusal("5047010700000000", 64).getMessage()).contains("starts with 0x5047, not 0x5046");
        PipeForgeException version = refusal("5046020700000000", 64);
        assertThat(version.code()).isEqualTo(ErrorCode.RPC_VERSION_MISMATCH);
        assertThat(version.params()).containsEntry("remote", "2.x");
        assertThat(refusal("5046010200000041", 64).getMessage()).contains("a frame of 65 bytes, more than 64");
        assertThat(refusal("50460109" + "00000000", 64).getMessage()).contains("unknown frame type 0x9");
        assertThat(refusal("50460107" + "00000001" + "00", 64).getMessage())
                .contains("1 bytes after a frame of type 7");
        assertThat(refusal("50460104" + "00000002" + "0000", 64).getMessage()).contains("malformed frame of type 4");
        assertThat(refusal("50460106" + "00000003" + "02" + "0000", 64).getMessage())
                .contains("hasChannel 2");
        assertThat(refusal("50460100" + "00000002" + "05" + "61", 64).getMessage())
                .contains("a string of 5 bytes in 1");
        String response = "50460102" + "00000013" + "0102030405060708090a0b0c0d0e0f10" + "00" + "00";
        assertThat(refusal(response.replace("00000013", "00000014") + "02" + "00", 64)
                        .getMessage())
                .contains("buffer type 2");
        assertThat(refusal(response.replace("00000013", "00000014") + "00" + "01", 64)
                        .getMessage())
                .contains("buffer flags 1 in version 1");
        assertThat(Handshake.protocolError(new IllegalStateException())).isNull();
        assertThatThrownBy(() ->
                        FrameCodec.varint(Unpooled.wrappedBuffer(HexFormat.of().parseHex("ffffffff7f"))))
                .hasMessageContaining("is not an int");
    }
}
